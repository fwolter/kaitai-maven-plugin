package name.valery1707.kaitai;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.impl.StaticLoggerBinder;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static java.lang.String.format;
import static name.valery1707.kaitai.KaitaiUtils.*;

/**
 * @see <a href="http://maven.apache.org/developers/mojo-api-specification.html">Mojo API Specification</a>
 */
@Mojo(
	name = "generate"
	, defaultPhase = LifecyclePhase.GENERATE_SOURCES
)
public class KaitaiMojo extends AbstractMojo {

	/**
	 * Version of <a href="http://kaitai.io/#download">KaiTai</a> library.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.version", defaultValue = "0.8")
	private String version;

	/**
	 * Direct link onto <a href="http://kaitai.io/#download">KaiTai universal zip archive</a>.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.url")
	private URL url;

	/**
	 * Cache directory for download KaiTai library.
	 *
	 * @see KaitaiMojo#version
	 * @see KaitaiMojo#url
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.cache")
	private File cacheDir;

	/**
	 * Source directory with <a href="http://formats.kaitai.io/">Kaitai Struct language</a> files.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.source", defaultValue = "${project.build.sourceDirectory}/../resources/kaitai")
	private File sourceDirectory;

	/**
	 * Include wildcard pattern list.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.includes", defaultValue = "*.ksy")
	private String[] includes;

	/**
	 * Exclude wildcard pattern list.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.excludes")
	private String[] excludes;

	/**
	 * Target directory for generated Java source files.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.output", defaultValue = "${project.build.directory}/generated-sources/kaitai")
	private File output;

	/**
	 * Target package for generated Java source files.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.package", defaultValue = "${project.groupId}")
	private String packageName;

	/**
	 * Skip plugin execution (don't read/validate any files, don't generate any java types).
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.skip", defaultValue = "false")
	private boolean skip = false;

	/**
	 * Overwrite exists files in target directory.
	 *
	 * @since 0.1.0
	 */
	@Parameter(property = "kaitai.overwrite", defaultValue = "false")
	private boolean overwrite = false;

	@Parameter(defaultValue = "${settings}", readonly = true)
	private Settings settings;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession session;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject project;

	/**
	 * Executes the plugin, to read the given source and behavioural properties and generate POJOs.
	 */
	public void execute() throws MojoExecutionException {
		try {
			executeInt();
		} catch (KaitaiException e) {
			throw new MojoExecutionException(e.getMessage(), e.getCause());
		}
	}

	private void executeInt() throws KaitaiException {
		if (skip) {
			getLog().info("Skip KaiTai generation: skip=true");
			return;
		}

		//Scan source files
		sourceDirectory = sourceDirectory.toPath().normalize().toFile();
		if (!sourceDirectory.exists()) {
			getLog().warn(format(
				"Skip KaiTai generation: Source directory does not exists: %s"
				, sourceDirectory.toPath().normalize().toFile().getAbsolutePath()
			));
			return;
		}
		List<Path> source = scanFiles(sourceDirectory.toPath(), includes, excludes);
		if (source.isEmpty()) {
			getLog().warn(format(
				"Skip KaiTai generation: Source directory does not contain KaiTai templates (include: %s; exclude: %s): %s"
				, Arrays.toString(includes)
				, Arrays.toString(excludes)
				, sourceDirectory.toPath().normalize().toFile().getAbsolutePath()
			));
			return;
		}

		StaticLoggerBinder.getSingleton().setMavenLog(getLog());
		Logger logger = StaticLoggerBinder.getSingleton().getLoggerFactory().getLogger(getClass().getName());

		//Download Kaitai distribution into cache and unzip it
		URL url = prepareUrl(this.url, version);
		Path cacheDir = prepareCache(detectCacheDir(), logger);
		Path kaitai = downloadKaitai(url, cacheDir, logger);

		//Generate Java sources
		Path output = mkdirs(this.output.toPath());
		Path generatedRoot;
		try {
			generatedRoot = KaitaiGenerator
				.generator(kaitai, output, packageName)
				.withSource(source)
				.overwrite(overwrite)
				.generate(logger);
		} catch (KaitaiException e) {
			throw new KaitaiException(
				"Fail to generate Java files"
				, e
			);
		}

		//Add generated directory into Maven's build scope
		project.addCompileSourceRoot(generatedRoot.normalize().toFile().getAbsolutePath());
	}

	private Path detectCacheDir() {
		if (cacheDir != null) {
			return cacheDir.toPath();
		} else {
			Path repository = new File(session.getLocalRepository().getBasedir()).toPath();
			return repository.resolve(".cache").resolve("kaitai").normalize();
		}
	}
}
