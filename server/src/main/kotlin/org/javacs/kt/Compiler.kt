package org.javacs.kt

import org.jetbrains.kotlin.com.intellij.codeInsight.NullableNotNullManager
import org.jetbrains.kotlin.com.intellij.lang.Language
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration as KotlinCompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.calls.components.InferenceSession
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.extensions.ExtraImportsProviderExtension
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar
import org.jetbrains.kotlin.scripting.compiler.plugin.definitions.CliScriptDefinitionProvider
import org.jetbrains.kotlin.scripting.configuration.ScriptingConfigurationKeys
import org.jetbrains.kotlin.scripting.definitions.ScriptCompilationConfigurationFromDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptDependenciesProvider
import org.jetbrains.kotlin.scripting.definitions.StandardScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.KotlinScriptDefinition // Legacy
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.getEnvironment
import org.jetbrains.kotlin.scripting.extensions.ScriptExtraImportsProviderExtension
import org.jetbrains.kotlin.scripting.resolve.KotlinScriptDefinitionFromAnnotatedTemplate
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices
import org.jetbrains.kotlin.util.KotlinFrontEndException
import org.jetbrains.kotlin.utils.PathUtil
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.net.URLClassLoader
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.script.dependencies.Environment
import kotlin.script.dependencies.ScriptContents
import kotlin.script.experimental.dependencies.ScriptDependencies
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.dependencies.DependenciesResolver
import kotlin.script.experimental.dependencies.DependenciesResolver.ResolveResult
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.configurationDependencies
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.JvmDependency
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.KotlinNullableNotNullManager
import org.javacs.kt.util.LoggingMessageCollector
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope

private val GRADLE_DSL_DEPENDENCY_PATTERN = Regex("^gradle-(?:kotlin-dsl|core).*\\.jar$")

/**
 * Kotlin compiler APIs used to parse, analyze and compile
 * files and expressions.
 */
private class CompilationEnvironment(
    javaSourcePath: Set<Path>,
    classPath: Set<Path>
) : Closeable {
    private val disposable = Disposer.newDisposable()

    val environment: KotlinCoreEnvironment
    val parser: KtPsiFactory
    val scripts: ScriptDefinitionProvider

    init {
        environment = KotlinCoreEnvironment.createForProduction(
            parentDisposable = disposable,
            // Not to be confused with the CompilerConfiguration in the language server Configuration
            configuration = KotlinCompilerConfiguration().apply {
                val langFeatures = mutableMapOf<LanguageFeature, LanguageFeature.State>()
                langFeatures[LanguageFeature.MultiPlatformProjects] = LanguageFeature.State.ENABLED
                val languageVersionSettings = LanguageVersionSettingsImpl(
                    LanguageVersion.LATEST_STABLE,
                    ApiVersion.createByLanguageVersion(LanguageVersion.LATEST_STABLE),
                    emptyMap(),
                    langFeatures
                )

                put(CommonConfigurationKeys.MODULE_NAME, JvmProtoBufUtil.DEFAULT_MODULE_NAME)
                put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, languageVersionSettings)
                put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, LoggingMessageCollector)
                add(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, ScriptingCompilerConfigurationComponentRegistrar())

                addJvmClasspathRoots(classPath.map { it.toFile() })
                addJavaSourceRoots(javaSourcePath.map { it.toFile() })

                // Setup script templates (e.g. used by Gradle's Kotlin DSL)
                val scriptDefinitions: MutableList<ScriptDefinition> = mutableListOf(ScriptDefinition.getDefault(defaultJvmScriptingHostConfiguration))

                if (classPath.any { GRADLE_DSL_DEPENDENCY_PATTERN.matches(it.fileName.toString()) }) {
                    LOG.info("Configuring Kotlin DSL script templates...")

                    val scriptTemplates = listOf(
                        "org.gradle.kotlin.dsl.KotlinInitScript",
                        "org.gradle.kotlin.dsl.KotlinSettingsScript",
                        "org.gradle.kotlin.dsl.KotlinBuildScript"
                    )

                    try {
                        // Load template classes
                        val scriptClassLoader = URLClassLoader(classPath.map { it.toUri().toURL() }.toTypedArray())
                        val fileClassPath = classPath.map { it.toFile() }
                        val scriptHostConfig = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
                            configurationDependencies(JvmDependency(fileClassPath))
                        }
                        // TODO: KotlinScriptDefinition will soon be deprecated, use
                        //       ScriptDefinition.compilationConfiguration and its defaultImports instead
                        //       of KotlinScriptDefinition.dependencyResolver
                        // TODO: Use ScriptDefinition.FromLegacyTemplate directly if possible
                        // scriptDefinitions = scriptTemplates.map { ScriptDefinition.FromLegacyTemplate(scriptHostConfig, scriptClassLoader.loadClass(it).kotlin) }
                        scriptDefinitions.addAll(scriptTemplates.map { ScriptDefinition.FromLegacy(scriptHostConfig, object : KotlinScriptDefinitionFromAnnotatedTemplate(
                            scriptClassLoader.loadClass(it).kotlin,
                            scriptHostConfig[ScriptingHostConfiguration.getEnvironment]?.invoke()
                        ) {
                            override val dependencyResolver: DependenciesResolver = object : DependenciesResolver {
                                override fun resolve(scriptContents: ScriptContents, environment: Environment) = ResolveResult.Success(ScriptDependencies(
                                    imports = listOf("org.gradle.kotlin.dsl.*")
                                ))
                            }
                        }) })
                    } catch (e: Exception) {
                        LOG.error("Error while loading script template classes")
                        LOG.printStackTrace(e)
                    }
                }

                LOG.info("Adding script definitions ${scriptDefinitions.map { it.asLegacyOrNull<KotlinScriptDefinition>()?.template?.simpleName }}")
                addAll(ScriptingConfigurationKeys.SCRIPT_DEFINITIONS, scriptDefinitions)
            },
            configFiles = EnvironmentConfigFiles.JVM_CONFIG_FILES
        )

        val project = environment.project
        if (project is MockProject) {
            project.registerService(NullableNotNullManager::class.java, KotlinNullableNotNullManager(project))
        }

        parser = KtPsiFactory(project)
        scripts = ScriptDefinitionProvider.getInstance(project)!! as CliScriptDefinitionProvider
    }

    fun updateConfiguration(config: CompilerConfiguration) {
        jvmTargetFrom(config.jvm.target)
            ?.let { environment.configuration.put(JVMConfigurationKeys.JVM_TARGET, it) }
    }

    private fun jvmTargetFrom(target: String): JvmTarget? = when (target) {
        // See https://github.com/JetBrains/kotlin/blob/master/compiler/frontend.java/src/org/jetbrains/kotlin/config/JvmTarget.kt
        "default" -> JvmTarget.DEFAULT
        "1.6" -> JvmTarget.JVM_1_6
        "1.8" -> JvmTarget.JVM_1_8
        // "9" -> JvmTarget.JVM_9
        // "10" -> JvmTarget.JVM_10
        // "11" -> JvmTarget.JVM_11
        // "12" -> JvmTarget.JVM_12
        else -> null
    }

    fun createContainer(sourcePath: Collection<KtFile>): Pair<ComponentProvider, BindingTraceContext> {
        val trace = CliBindingTrace()
        val container = TopDownAnalyzerFacadeForJVM.createContainer(
            project = environment.project,
            files = sourcePath,
            trace = trace,
            configuration = environment.configuration,
            packagePartProvider = environment::createPackagePartProvider,
            // TODO FileBasedDeclarationProviderFactory keeps indices, re-use it across calls
            declarationProviderFactory = ::FileBasedDeclarationProviderFactory
        )
        return Pair(container, trace)
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}

/**
 * Determines the compilation environment used
 * by the compiler (and thus the class path).
 */
enum class CompilationKind {
    /** Uses the default class path. */
    DEFAULT,
    /** Uses the Kotlin DSL class path if available. */
    BUILD_SCRIPT
}

/**
 * Incrementally compiles files and expressions.
 * The basic strategy for compiling one file at-a-time is outlined in OneFilePerformance.
 */
class Compiler(javaSourcePath: Set<Path>, classPath: Set<Path>, buildScriptClassPath: Set<Path> = emptySet()) : Closeable {
    private var closed = false
    private val localFileSystem: VirtualFileSystem

    private val defaultCompileEnvironment = CompilationEnvironment(javaSourcePath, classPath)
    private val buildScriptCompileEnvironment = buildScriptClassPath.takeIf { it.isNotEmpty() }?.let { CompilationEnvironment(emptySet(), it) }
    private val compileLock = ReentrantLock() // TODO: Lock at file-level

    companion object {
        init {
            setIdeaIoUseFallback()
        }
    }

    init {
        localFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)
    }

    /**
     * Updates the compiler environment using the given
     * configuration (which is a class from this project).
     */
    fun updateConfiguration(config: CompilerConfiguration) {
        defaultCompileEnvironment.updateConfiguration(config)
        buildScriptCompileEnvironment?.updateConfiguration(config)
    }

    fun createPsiFile(content: String, file: Path = Paths.get("dummy.virtual.kt"), language: Language = KotlinLanguage.INSTANCE, kind: CompilationKind = CompilationKind.DEFAULT): PsiFile {
        assert(!content.contains('\r'))

        val new = psiFileFactoryFor(kind).createFileFromText(file.toString(), language, content, true, false)
        assert(new.virtualFile != null)

        return new
    }

    fun createKtFile(content: String, file: Path = Paths.get("dummy.virtual.kt"), kind: CompilationKind = CompilationKind.DEFAULT): KtFile =
            createPsiFile(content, file, language = KotlinLanguage.INSTANCE, kind = kind) as KtFile

    fun createKtExpression(content: String, file: Path = Paths.get("dummy.virtual.kt"), kind: CompilationKind = CompilationKind.DEFAULT): KtExpression {
        val property = createKtDeclaration("val x = $content", file, kind) as KtProperty
        return property.initializer!!
    }

    fun createKtDeclaration(content: String, file: Path = Paths.get("dummy.virtual.kt"), kind: CompilationKind = CompilationKind.DEFAULT): KtDeclaration {
        val parse = createKtFile(content, file, kind)
        val declarations = parse.declarations

        assert(declarations.size == 1) { "${declarations.size} declarations in $content" }

        val onlyDeclaration = declarations.first()

        if (onlyDeclaration is KtScript) {
            val scriptDeclarations = onlyDeclaration.declarations

            assert(declarations.size == 1) { "${declarations.size} declarations in script in $content" }

            return scriptDeclarations.first()
        }
        else return onlyDeclaration
    }

    private fun compileEnvironmentFor(kind: CompilationKind): CompilationEnvironment = when (kind) {
        CompilationKind.DEFAULT -> defaultCompileEnvironment
        CompilationKind.BUILD_SCRIPT -> buildScriptCompileEnvironment ?: defaultCompileEnvironment
    }

    fun psiFileFactoryFor(kind: CompilationKind): PsiFileFactory =
        PsiFileFactory.getInstance(compileEnvironmentFor(kind).environment.project)

    fun compileKtFile(file: KtFile, sourcePath: Collection<KtFile>, kind: CompilationKind = CompilationKind.DEFAULT): Pair<BindingContext, ComponentProvider> =
        compileKtFiles(listOf(file), sourcePath, kind)

    fun compileKtFiles(files: Collection<KtFile>, sourcePath: Collection<KtFile>, kind: CompilationKind = CompilationKind.DEFAULT): Pair<BindingContext, ComponentProvider> {
        if (kind == CompilationKind.BUILD_SCRIPT) {
            // Print the (legacy) script template used by the compiled Kotlin DSL build file
            files.forEach { LOG.debug { "$it -> ScriptDefinition: ${it.findScriptDefinition()?.asLegacyOrNull<KotlinScriptDefinition>()?.template?.simpleName}" } }
        }

        compileLock.withLock {
            val compileEnv = compileEnvironmentFor(kind)
            val (container, trace) = compileEnv.createContainer(sourcePath)
            container.get<LazyTopDownAnalyzer>().analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files)
            return Pair(trace.bindingContext, container)
        }
    }

    fun compileKtExpression(expression: KtExpression, scopeWithImports: LexicalScope, sourcePath: Collection<KtFile>, kind: CompilationKind = CompilationKind.DEFAULT): Pair<BindingContext, ComponentProvider> {
        try {
            // Use same lock as 'compileFile' to avoid concurrency issues such as #42
            compileLock.withLock {
                val (container, trace) = compileEnvironmentFor(kind).createContainer(sourcePath)
                val incrementalCompiler = container.get<ExpressionTypingServices>()
                incrementalCompiler.getTypeInfo(
                        scopeWithImports,
                        expression,
                        TypeUtils.NO_EXPECTED_TYPE,
                        DataFlowInfo.EMPTY,
                        InferenceSession.default,
                        trace,
                        true)
                return Pair(trace.bindingContext, container)
            }
        } catch (e: KotlinFrontEndException) {
            throw KotlinLSException("Error while analyzing: ${describeExpression(expression.text)}", e)
        }
    }

    override fun close() {
        if (!closed) {
            defaultCompileEnvironment.close()
            buildScriptCompileEnvironment?.close()
            closed = true
        } else {
            LOG.warn("Compiler is already closed!")
        }
    }
}

private fun describeExpression(expression: String): String = expression.lines().let { lines ->
    if (lines.size < 5) {
        expression
    } else {
        (lines.take(3) + listOf("...", lines.last())).joinToString(separator = "\n")
    }
}
