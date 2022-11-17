package com.blazemeter.jmeter.correlation.regression;

import static com.blazemeter.jmeter.correlation.regression.FileTemplateAssert.assertThat;

import com.google.common.io.Resources;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.abstracta.jmeter.javadsl.core.EmbeddedJmeterEngine.JMeterEnvironment;
import us.abstracta.jmeter.javadsl.core.listeners.JtlWriter;

/**
 * This class is used for running regression tests, but also provides logic (in main method) to
 * generate baselines for initialization of regression tests.
 *
 * Regression tests need to be at src/test/resources/regression/<CLIENT>/<FLOW>. recording.jtl.xml
 * has to be in the folder, and rest of needed files can be generated (into
 * target/regression-baseline) by running main on this class. Then validate generated files with
 * JMeter GUI & copy them to the correct folder in src/test/resources/regression. If for some client
 * there are specific user.properties, you can put the associated user.properties in
 * src/test/resources/regression/<CLIENT> directory.
 */
@RunWith(Parameterized.class)
public class RegressionIT {

  private static final Logger LOG = LoggerFactory.getLogger(RegressionIT.class);
  private static final Path RECORDING_TEMPLATE_PATH = getResourcePath(
      "/templates/correlation-recorder.jmx");
  private static final Path BASE_REGRESSION_TESTS_PATH = getResourcePath("/regression");
  private static final String RECORDING_JTL_NAME = "recording.jtl.xml";
  private static final String RECORDED_JMX_NAME = "recorded.jmx";
  private static final String RECORDED_JMX_TEMPLATE_NAME = "recorded.jmx.xml";
  private static final String TEST_RUN_JTL_NAME = "test-run.jtl";
  private static final String TEST_RUN_JTL_TEMPLATE_NAME = "test-run.jtl.xml";

  private static JMeterEnvironment jMeterEnvironment;

  @BeforeClass
  public static void setupClass() throws IOException {
    jMeterEnvironment = new JMeterEnvironment();
    addPluginClassesToJMeterSearchPaths();
  }

  private static void addPluginClassesToJMeterSearchPaths() {
    Properties props = JMeterUtils.getJMeterProperties();
    String searchPathsPropName = "search_paths";
    props.setProperty(searchPathsPropName,
        props.getProperty(searchPathsPropName) + ";" + Paths.get("target", "classes"));
  }

  @AfterClass
  public static void tearDownClass() throws IOException {
    if (jMeterEnvironment != null) {
      jMeterEnvironment.close();
    }
  }

  private static Path getResourcePath(String resourceName) {
    try {
      return Paths.get(Resources.getResource(RegressionIT.class, resourceName).toURI());
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() throws IOException {
    return findRegressionTestDirs(BASE_REGRESSION_TESTS_PATH).stream()
        .map(p -> new Object[]{BASE_REGRESSION_TESTS_PATH.relativize(p).toString(), p})
        .collect(Collectors.toList());
  }

  private static List<Path> findRegressionTestDirs(Path searchPath) throws IOException {
    return Files.walk(searchPath)
        .filter(p -> p.endsWith(RECORDING_JTL_NAME))
        .map(Path::getParent)
        .collect(Collectors.toList());
  }

  @Parameter
  public String regressionTestName;

  @Parameter(1)
  public Path regressionTestDir;

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void test() throws Exception {
    Path recordingJtl = regressionTestDir.resolve(RECORDING_JTL_NAME);
    try (UserPropertiesContext userPros = new UserPropertiesContext(regressionTestDir);
        ServerMock serverMock = ServerMock.fromJtl(recordingJtl);
        Recording recording = Recording.fromTemplate(RECORDING_TEMPLATE_PATH)) {
      ClientMock.fromJtl(recordingJtl).run();
      Path recordedTestPlan = tempFolder.newFile(RECORDED_JMX_NAME).toPath();
      recording.saveRecordingTo(recordedTestPlan);
      assertThat(recordedTestPlan).matches(regressionTestDir.resolve(RECORDED_JMX_TEMPLATE_NAME));
      serverMock.reset();
      Path testRunJtl = tempFolder.newFile(TEST_RUN_JTL_NAME).toPath();
      runTest(recordedTestPlan, testRunJtl);
      assertThat(testRunJtl).matches(regressionTestDir.resolve(TEST_RUN_JTL_TEMPLATE_NAME));
    }
  }

  private static class UserPropertiesContext implements Closeable {

    private final Map<String, Object> defaultProperties;

    private UserPropertiesContext(Path regressionTestDir) throws IOException {
      defaultProperties = (Map<String, Object>) JMeterUtils.getJMeterProperties().clone();
      JMeterUtils.getJMeterProperties().putAll(findUserProperties(regressionTestDir));
    }

    private Properties findUserProperties(Path regressionTestPath) throws IOException {
      Path currentPath = regressionTestPath;
      while (!currentPath.equals(BASE_REGRESSION_TESTS_PATH)) {
        File userPropertiesFile = currentPath.resolve("user.properties").toFile();
        if (userPropertiesFile.exists()) {
          Properties ret = new Properties();
          ret.load(new FileReader(userPropertiesFile));
          return ret;
        } else {
          currentPath = currentPath.getParent();
        }
      }
      return new Properties();
    }

    @Override
    public void close() {
      Properties props = JMeterUtils.getJMeterProperties();
      props.clear();
      props.putAll(defaultProperties);
    }
  }

  private static void runTest(Path recordedTestPlan, Path jtlFilePath) throws IOException {
    jtlFilePath.toFile().delete();
    HashTree testPlan = SaveService.loadTree(recordedTestPlan.toFile());
    addJtlWriter(jtlFilePath, testPlan);
    StandardJMeterEngine engine = new StandardJMeterEngine();
    engine.configure(testPlan);
    engine.run();
  }

  private static void addJtlWriter(Path ret, HashTree testPlan) {
    new JtlWriter(ret.toString())
        .withAllFields(true)
        .buildTreeUnder(testPlan.values().iterator().next());
  }

  public static void main(String[] args) throws IOException {
    Path subDirPath =
        args.length > 0 ? BASE_REGRESSION_TESTS_PATH.resolve(args[0]) : BASE_REGRESSION_TESTS_PATH;
    generateBaselinesTo(Paths.get("target", "regression-baseline"), subDirPath);
  }

  private static void generateBaselinesTo(Path baselineBasePath, Path subDirPath)
      throws IOException {
    try (JMeterEnvironment env = new JMeterEnvironment()) {
      addPluginClassesToJMeterSearchPaths();
      for (Path regressionTestDir : findRegressionTestDirs(subDirPath)) {
        Path regressionTestRelativePath = BASE_REGRESSION_TESTS_PATH.relativize(regressionTestDir);
        Path regressionTestBaseLinePath = baselineBasePath.resolve(regressionTestRelativePath);
        regressionTestBaseLinePath.toFile().mkdirs();
        Path recordingJtl = regressionTestDir.resolve(RECORDING_JTL_NAME);
        Files.copy(recordingJtl, regressionTestBaseLinePath.resolve(RECORDING_JTL_NAME),
            StandardCopyOption.REPLACE_EXISTING);
        Path recordedTestPlan = regressionTestBaseLinePath.resolve(RECORDED_JMX_NAME);
        Path testRunJtl = regressionTestBaseLinePath.resolve(TEST_RUN_JTL_NAME);
        LOG.info("Creating baseline for '{}'", regressionTestRelativePath.toString());
        try (UserPropertiesContext userProps = new UserPropertiesContext(regressionTestDir);
            ServerMock serverMock = ServerMock.fromJtl(recordingJtl);
            Recording recording = Recording.fromTemplate(RECORDING_TEMPLATE_PATH)) {
          ClientMock.fromJtl(recordingJtl).run();
          recording.saveRecordingTo(recordedTestPlan);
          serverMock.reset();
          runTest(recordedTestPlan, testRunJtl);
          convertJmxToTemplate(recordedTestPlan,
              regressionTestBaseLinePath.resolve(RECORDED_JMX_TEMPLATE_NAME));
          convertJtlToTemplate(testRunJtl, regressionTestBaseLinePath.resolve(
              TEST_RUN_JTL_TEMPLATE_NAME));
        } catch (Exception e) {
          LOG.error("Problem generating baseline for '{}'", regressionTestRelativePath.toString(),
              e);
        } finally {
          recordedTestPlan.toFile().delete();
          testRunJtl.toFile().delete();
        }
      }
    }
  }

  private static void convertJmxToTemplate(Path jmxPath, Path templatePath) throws IOException {
    convertFileToTemplate(jmxPath, templatePath, Arrays.asList(
        buildUuidReplacement("cacheKey\">", "<"),
        buildReplacement("testname=\"", "\\d+", " ")));
  }

  private static StringReplacement buildUuidReplacement(String prefix, String suffix) {
    return buildReplacement(prefix, "\\w+-\\w+-\\w+-\\w+-\\w+", suffix);
  }

  private static StringReplacement buildReplacement(String prefix, String regex, String suffix) {
    return new StringReplacement(prefix + regex + suffix,
        prefix + "{{" + regex.replace("\\", "\\\\") + "}}" + suffix);
  }

  private static void convertFileToTemplate(Path jmxPath, Path templatePath,
      List<StringReplacement> replacements)
      throws IOException {
    String templateContents = new String(Files.readAllBytes(jmxPath), StandardCharsets.UTF_8);
    for (StringReplacement replacement : replacements) {
      templateContents = replacement.apply(templateContents);
    }
    try (FileWriter fw = new FileWriter(templatePath.toFile())) {
      fw.write(templateContents);
    }
  }

  private static void convertJtlToTemplate(Path jtlPath, Path templatePath) throws IOException {
    convertFileToTemplate(jtlPath, templatePath, Arrays.asList(
        buildNumericAttributeReplacement("t"),
        buildNumericAttributeReplacement("lt"),
        buildNumericAttributeReplacement("ct"),
        buildNumericAttributeReplacement("ts"),
        buildReplacement(" lb=\"", "\\d+", " "),
        buildReplacement(" hn=\"", ".*?", "\">"),
        buildUuidReplacement("Matched-Stub-Id: ", ""),
        buildReplacement("SWETS=", "\\d+", "")));
  }

  private static StringReplacement buildNumericAttributeReplacement(String attributeName) {
    return buildReplacement(" " + attributeName + "=\"", "\\d+", "\" ");
  }

}
