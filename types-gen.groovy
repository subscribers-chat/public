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
import java.util.regex.Matcher

StaticJavaParser.parserConfiguration.languageLevel = ParserConfiguration.LanguageLevel.BLEEDING_EDGE

if (args.length < 2) {
    System.err.println "usage: groovy types-gen.groovy <source-root> <out-dir> [package-prefix]"
    System.err.println ""
    System.err.println "  source-root     Java source root or any ancestor (e.g. ../ for monorepo root)"
    System.err.println "  out-dir         directory for generated JSON (e.g. src/types)"
    System.err.println "  package-prefix  limit scan to this package (default: chat.subscribers)"
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

def literal
literal = { Expression expr ->
    if (expr instanceof StringLiteralExpr)   return expr.value
    if (expr instanceof IntegerLiteralExpr)  return expr.asInt()
    if (expr instanceof LongLiteralExpr)     return expr.asLong()
    if (expr instanceof DoubleLiteralExpr)   return expr.asDouble()
    if (expr instanceof BooleanLiteralExpr)  return expr.value
    if (expr instanceof CharLiteralExpr)     return expr.value as String
    if (expr instanceof NullLiteralExpr)     return null
    if (expr instanceof FieldAccessExpr)     return expr.nameAsString
    if (expr instanceof NameExpr)            return expr.nameAsString
    if (expr instanceof UnaryExpr) {
        def inner = literal(expr.expression)
        return (expr.operator.asString() == '-' && inner instanceof Number) ? -inner : inner
    }
    if (expr instanceof MethodCallExpr) {
        def name = expr.nameAsString
        if (name in ['of', 'asList']) return expr.arguments.collect { literal(it) }
        return expr.toString()
    }
    if (expr instanceof ArrayInitializerExpr) return expr.values.collect { literal(it) }
    expr.toString()
}

// --- git context for the output repo ----------------------------------------
def findGitRoot = { File start ->
    def cur = start
    while (cur != null) {
        if (new File(cur, '.git').exists()) return cur
        cur = cur.parentFile
    }
    null
}

def gitInfo = { File repoRoot ->
    def run = { List cmd ->
        def proc = cmd.execute(null, repoRoot)
        proc.waitForOrKill(5000)
        proc.exitValue() == 0 ? proc.text.trim() : null
    }
    def remote = run(['git', 'remote', 'get-url', 'origin'])
    def branch = run(['git', 'rev-parse', '--abbrev-ref', 'HEAD'])
    if (!remote || !branch || branch == 'HEAD') return null
    def m = remote =~ /(?:github\.com[:\/])([^\/]+)\/([^\/.]+?)(?:\.git)?$/
    if (!m) return null
    [slug: "${m[0][1]}/${m[0][2]}", branch: branch]
}

def outRepoRoot  = findGitRoot(outDir.toFile())
def info         = outRepoRoot ? gitInfo(outRepoRoot) : null
def baseUrl      = info ? "https://raw.githubusercontent.com/${info.slug}/refs/heads/${info.branch}" : null
def relTypesPath = outRepoRoot ? outRepoRoot.toPath().relativize(outDir).toString().replace('\\', '/') : null

// --- generate ---------------------------------------------------------------
def gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

def entries = []
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

        entries << [
                typeName: e.nameAsString,
                filename: filename,
                // .toString() forces the GString into a plain String — without it
                // Gson recursively serialises GString.values and emits garbage.
                url     : baseUrl ? "${baseUrl}/${relTypesPath}/${filename}".toString() : null,
        ]
        println "wrote ${outFile}  (${constants.size()} constants)"
        written++
    }
}

entries.sort { it.typeName }

// --- index.json -------------------------------------------------------------
if (outRepoRoot) {
    def indexPath = outRepoRoot.toPath().resolve('index.json').toFile()
    def indexData = entries.collect { [typeName: it.typeName, url: it.url] }
    indexPath.withWriter('UTF-8') { w -> gson.toJson(indexData, w) }
    println "wrote ${indexPath}"
}

// --- README.md --------------------------------------------------------------
def README_START = '<!-- types:start -->'
def README_END   = '<!-- types:end -->'

if (outRepoRoot) {
    def readmePath = outRepoRoot.toPath().resolve('README.md').toFile()

    def list = entries.collect {
        it.url ? "- [${it.typeName}](${it.url})" : "- ${it.typeName}"
    }.join('\n')
    def block = "${README_START}\n${list}\n${README_END}"

    if (readmePath.exists()) {
        def content = readmePath.text
        if (content.contains(README_START) && content.contains(README_END)) {
            def updated = content.replaceFirst(
                    /(?s)\Q${README_START}\E.*?\Q${README_END}\E/,
                    Matcher.quoteReplacement(block)
            )
            readmePath.text = updated
        } else {
            // README has hand-written content but no sentinels — append a managed section
            // rather than overwriting whatever the human put there.
            readmePath.text = content.trimEnd() + "\n\n## Catalogs\n\n${block}\n"
        }
    } else {
        readmePath.text = """\
# public

Static type catalogs generated from `${pkgPrefix}.*` enums by
[`types-gen.groovy`](./types-gen.groovy). Hosted as raw JSON for the Tauri
client to fetch without going through the running services.

The list below is auto-managed — anything between the sentinels gets
rewritten on the next run. Edit anywhere else freely.

## Catalogs

${block}

## Regenerating

```sh
groovy types-gen.groovy ../ ./src/types
```

Walks the source tree recursively, picks up every enum under
`${pkgPrefix}.*`, writes one JSON file per enum into `${relTypesPath ?: 'src/types'}/`,
and refreshes `index.json` + this README.
"""
    }
    println "wrote ${readmePath}"
}

println ""
println "${written} enum(s) generated under '${pkgPrefix}'"
if (skipped) {
    println ""
    println "skipped:"
    skipped.each { println "  $it" }
}

// --- commit + push ----------------------------------------------------------
if (outRepoRoot) {
    def runGit = { List<String> gitArgs, boolean failOk = false ->
        def cmd = ['git', '-C', outRepoRoot.absolutePath] + gitArgs
        def proc = cmd.execute()
        proc.waitForProcessOutput(System.out, System.err)
        if (proc.exitValue() != 0 && !failOk) {
            System.err.println "git ${gitArgs.join(' ')} failed (exit ${proc.exitValue()})"
            System.exit(3)
        }
        proc.exitValue()
    }

    println ""
    println "committing & pushing..."
    runGit(['add', '.'])

    // `git diff --cached --quiet` exits 0 when nothing is staged, 1 when there's a diff.
    if (runGit(['diff', '--cached', '--quiet'], true) == 0) {
        println "nothing changed — skipping commit"
    } else {
        runGit(['commit', '-m', 'types - generation'])
        runGit(['push', 'origin', 'main'])
    }
}
