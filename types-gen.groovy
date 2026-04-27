@Grab('com.github.javaparser:javaparser-core:3.26.2')
@Grab('com.google.code.gson:gson:2.11.0')

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.expr.*
import com.google.gson.GsonBuilder

import java.nio.file.Path

// Match (or beat) the source's language level so records / `_` parse cleanly.
StaticJavaParser.parserConfiguration.languageLevel = ParserConfiguration.LanguageLevel.BLEEDING_EDGE

if (args.length < 2) {
    System.err.println "usage: groovy types-gen.groovy <source-root> <out-dir> [package-prefix]"
    System.err.println ""
    System.err.println "  source-root     Java source root"
    System.err.println "                  e.g. ../user-service/src/main/java"
    System.err.println "  out-dir         directory for generated JSON"
    System.err.println "                  e.g. src/types"
    System.err.println "  package-prefix  limit scan to this package"
    System.err.println "                  (default: chat.subscribers)"
    System.exit(1)
}

def sourceRoot = Path.of(args[0]).toAbsolutePath().normalize()
def outDir     = Path.of(args[1]).toAbsolutePath().normalize()
def pkgPrefix  = args.length > 2 ? args[2] : 'chat.subscribers'

if (!sourceRoot.toFile().isDirectory()) {
    System.err.println "source-root not found: $sourceRoot"
    System.exit(2)
}

outDir.toFile().mkdirs()

// Directories we never want to descend into during a recursive walk.
def EXCLUDED_DIRS = [
        'target', 'build', 'out', 'dist',
        '.git', '.idea', '.gradle', '.mvn', '.vscode',
        'node_modules', '.next', '.cache',
] as Set

// --- helpers ----------------------------------------------------------------
def kebab = { String s ->
    s.replaceAll(/([a-z0-9])([A-Z])/, '$1-$2')
            .replaceAll(/([A-Z]+)([A-Z][a-z])/, '$1-$2')
            .toLowerCase()
}

// Resolve a literal expression to its Java value.
// Anything we can't interpret statically is rendered as its source text so the
// output is at least debuggable.
def literal
literal = { Expression expr ->
    if (expr instanceof StringLiteralExpr)   return expr.value
    if (expr instanceof IntegerLiteralExpr)  return expr.asInt()
    if (expr instanceof LongLiteralExpr)     return expr.asLong()
    if (expr instanceof DoubleLiteralExpr)   return expr.asDouble()
    if (expr instanceof BooleanLiteralExpr)  return expr.value
    if (expr instanceof CharLiteralExpr)     return expr.value as String
    if (expr instanceof NullLiteralExpr)     return null
    if (expr instanceof FieldAccessExpr)     return expr.nameAsString          // FontCategory.SANS_SERIF -> "SANS_SERIF"
    if (expr instanceof NameExpr)            return expr.nameAsString
    if (expr instanceof UnaryExpr) {
        def inner = literal(expr.expression)
        return (expr.operator.asString() == '-' && inner instanceof Number) ? -inner : inner
    }
    if (expr instanceof MethodCallExpr) {
        // Treat factory calls (List.of, Set.of, Arrays.asList, Map.of) as collection literals.
        def name = expr.nameAsString
        if (name in ['of', 'asList']) return expr.arguments.collect { literal(it) }
        return expr.toString()
    }
    if (expr instanceof ArrayInitializerExpr) return expr.values.collect { literal(it) }
    expr.toString()
}

// --- discovery + extraction -------------------------------------------------
def gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

def written = 0
def skipped = []

sourceRoot.toFile().traverse(
        type: groovy.io.FileType.FILES,
        nameFilter: ~/.*\.java$/,
        preDir: { dir ->
            dir.name in EXCLUDED_DIRS
                    ? groovy.io.FileVisitResult.SKIP_SUBTREE
                    : groovy.io.FileVisitResult.CONTINUE
        }
) { file ->
    def rel = sourceRoot.relativize(file.toPath()).toString().replace('\\', '/')

    def cu
    try {
        cu = StaticJavaParser.parse(file)
    } catch (Throwable t) {
        skipped << "${rel}  (parse failed: ${t.message})"
        return
    }

    // Filter by the file's package declaration, not its filesystem path.
    // Lets the script run from any root above the source — repo root, monorepo root, /tmp, whatever.
    def pkg = cu.packageDeclaration.map { it.nameAsString }.orElse('')
    if (!(pkg == pkgPrefix || pkg.startsWith(pkgPrefix + '.'))) return

    cu.findAll(EnumDeclaration).each { EnumDeclaration e ->
        def ctors = e.constructors

        def constants = e.entries.collect { EnumConstantDeclaration c ->
            def out = [name: c.nameAsString] as LinkedHashMap

            def args = c.arguments
            if (args.size() > 0) {
                ConstructorDeclaration ctor = ctors.find { it.parameters.size() == args.size() }
                if (ctor) {
                    args.eachWithIndex { Expression argExpr, int i ->
                        out[ctor.parameters[i].nameAsString] = literal(argExpr)
                    }
                } else {
                    args.eachWithIndex { Expression argExpr, int i ->
                        out["arg${i}"] = literal(argExpr)
                    }
                }
            }
            out
        }

        def filename = kebab(e.nameAsString) + '.json'
        def outFile  = outDir.resolve(filename).toFile()
        outFile.withWriter('UTF-8') { w -> gson.toJson(constants, w) }
        println "wrote ${outFile}  (${constants.size()} constants)"
        written++
    }
}

println ""
println "${written} enum(s) generated under '${pkgPrefix}'"
if (skipped) {
    println ""
    println "skipped:"
    skipped.each { println "  $it" }
}
