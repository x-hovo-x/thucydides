package net.thucydides.core.requirements;

import ch.lambdaj.function.convert.Converter;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.requirements.model.Narrative;
import net.thucydides.core.requirements.model.NarrativeReader;
import net.thucydides.core.requirements.model.Requirement;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.util.Inflector;
import org.apache.commons.lang.StringUtils;

import javax.persistence.Transient;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static ch.lambdaj.Lambda.convert;
import static net.thucydides.core.requirements.RequirementsPath.pathElements;
import static net.thucydides.core.requirements.RequirementsPath.stripRootFromPath;

/**
 * Load a set of requirements (epics/themes,...) from the directory structure.
 * This will typically be the directory structure containing the tests (for JUnit) or stories (e.g. for JBehave).
 * By default, the tests
 */
public class FileSystemRequirementsTagProvider implements RequirementsTagProvider {

    public final static List<String> DEFAULT_CAPABILITY_TYPES = ImmutableList.of("capability","feature");

    private final static String DEFAULT_ROOT_DIRECTORY = "stories";
    private final static String DEFAULT_RESOURCE_DIRECTORY = "src/test/resources";
//    private final static Pattern PATH_SEPARATORS = Pattern.compile("[\\\\/.]");
    private static final String WORKING_DIR = "user.dir";
    private static final List<Requirement> NO_REQUIREMENTS = Lists.newArrayList();
    private static final List<TestTag> NO_TEST_TAGS = Lists.newArrayList();

    private final String rootDirectoryPath;
    private final NarrativeReader narrativeReader;
    private final int level;
    private final EnvironmentVariables environmentVariables;

    @Transient
    private List<Requirement> requirements;

    public FileSystemRequirementsTagProvider() {
        this(DEFAULT_ROOT_DIRECTORY);
    }

    public FileSystemRequirementsTagProvider(String rootDirectory, int level) {
        this(rootDirectory, level, Injectors.getInjector().getInstance(EnvironmentVariables.class));
    }

    public FileSystemRequirementsTagProvider(String rootDirectory, int level, EnvironmentVariables environmentVariables) {
        this.environmentVariables = environmentVariables;
        this.rootDirectoryPath = rootDirectory;
        this.level = level;
        this.narrativeReader = NarrativeReader.forRootDirectory(rootDirectory)
                                              .withCapabilityTypes(getCapabilityTypes());
    }

    public FileSystemRequirementsTagProvider(String rootDirectory) {
        this(rootDirectory, 0);
    }

    /**
     * We look for file system requirements in the root directory path (by default, 'stories').
     * First, we look on the classpath. If we don't find anything on the classpath (e.g. if the task is
     * being run from the Maven plugin), we look in the src/main/resources and src/test/resources directories starting
     * at the working directory.
     */
    public List<Requirement> getRequirements() {
        if (requirements == null) {
            URL rootDirectoryPath = null;
            try {
                Optional<String> directoryPath = getRootDirectoryPath();
                if (directoryPath.isPresent()) {
                    File rootDirectory = new File(directoryPath.get());
                    File[] capabilityDirectories = rootDirectory.listFiles(thatAreDirectories());
                    requirements = loadCapabilitiesFrom(capabilityDirectories);
                    Collections.sort(requirements);
                } else {
                    requirements = NO_REQUIREMENTS;
                }
            } catch (IOException e) {
                requirements = NO_REQUIREMENTS;
                throw new IllegalArgumentException("Could not load requirements from '" + rootDirectoryPath + "'", e);
            }
        }
        return requirements;
    }

    private Optional<String> getRootDirectoryPath() throws IOException {
        Optional<String> rootDirectoryOnClasspath = getRootDirectoryFromClasspath();
        if (rootDirectoryOnClasspath.isPresent()) {
            return rootDirectoryOnClasspath;
        } else {
            return getRootDirectoryFromWorkingDirectory();
        }
    }


    public Optional<String> getRootDirectoryFromClasspath() throws IOException {
        Enumeration<URL> requirementResources = getDirectoriesFrom(rootDirectoryPath);
        if (requirementResources.hasMoreElements()) {
            return Optional.of(requirementResources.nextElement().getPath());
        } else {
            return Optional.absent();
        }
    }

    public Optional<String> getRootDirectoryFromWorkingDirectory() throws IOException {
        File workingDirectory = new File(System.getProperty(WORKING_DIR));
        File resourceDirectory = new File(workingDirectory, DEFAULT_RESOURCE_DIRECTORY);
        File requirementsDirectory = new File(resourceDirectory, rootDirectoryPath);
        if (requirementsDirectory.exists()) {
            return Optional.of(requirementsDirectory.getAbsolutePath());
        } else {
            return Optional.absent();
        }
    }

    private Enumeration<URL> getDirectoriesFrom(String root) throws IOException {
        return getClass().getClassLoader().getResources(root);
    }

    public Set<TestTag> getTagsFor(final TestOutcome testOutcome) {
        Set<TestTag> tags = new HashSet<TestTag>();
        if (testOutcome.getPath() != null) {
            List<String> storyPathElements = stripRootFrom(pathElements(stripRootPathFrom(testOutcome.getPath())));
            tags.addAll(getMatchingCapabilities(getRequirements(), storyPathElements));
        }
        return tags;
    }

    private List<TestTag> getMatchingCapabilities(List<Requirement> requirements, List<String> storyPathElements) {
        if (storyPathElements.isEmpty()) {
            return NO_TEST_TAGS;
        } else {
            Optional<Requirement> matchingCapability = findMatchingCapabilityIn(next(storyPathElements), requirements);
            if (matchingCapability.isPresent()) {
                TestTag thisTag = TestTag.withName(matchingCapability.get().getName()).andType(matchingCapability.get().getType());
                List<TestTag> remainingTags = getMatchingCapabilities(matchingCapability.get().getChildren(), tail(storyPathElements));
                return concat(thisTag, remainingTags);
            } else {
                return NO_TEST_TAGS;
            }
        }
    }

    private List<String> stripRootFrom(List<String> storyPathElements) {
        return stripRootFromPath(rootDirectoryPath, storyPathElements);
    }

    private String stripRootPathFrom(String testOutcomePath) {
        String rootPath = ThucydidesSystemProperty.TEST_ROOT_PACKAGE.from(environmentVariables);
        if (rootPath != null && testOutcomePath.startsWith(rootPath)) {
            return testOutcomePath.substring(rootPath.length() + 1);
        } else {
            return testOutcomePath;
        }
    }

    private List<TestTag> concat(TestTag thisTag, List<TestTag> remainingTags) {
        List<TestTag> totalTags = new ArrayList<TestTag>();
        totalTags.add(thisTag);
        totalTags.addAll(remainingTags);
        return totalTags;
    }

    private <T> T next(List<T> elements) {
        return elements.get(0);
    }

    private <T> List<T> tail(List<T> elements) {
        return elements.subList(1, elements.size());
    }

    private Optional<Requirement> findMatchingCapabilityIn(String storyPathElement, List<Requirement> requirements) {
        for(Requirement requirement : requirements) {
            String normalizedStoryPathElement = Inflector.getInstance().humanize(storyPathElement);
            if (requirement.getName().equals(normalizedStoryPathElement)) {
                return Optional.of(requirement);
            }
        }
        return Optional.absent();
    }

    private List<Requirement> loadCapabilitiesFrom(File[] capabilityDirectories) {
        return convert(capabilityDirectories, toCapabilities());
    }

    private Converter<File,Requirement> toCapabilities() {
        return new Converter<File, Requirement>() {

            @Override
            public Requirement convert(File capabilityFileOrDirectory) {
                return readCapabilityFrom(capabilityFileOrDirectory);
            }
        };
    }

    private Requirement readCapabilityFrom(File capabilityDirectory) {
        Optional<Narrative> capabilityNarrative = narrativeReader.loadFrom(capabilityDirectory, level);
        if (capabilityNarrative.isPresent()) {
            return requirementWithNarrative(capabilityDirectory, capabilityNarrative);
        } else {
            return requirementFromDirectoryName(capabilityDirectory);
        }
    }

    private Requirement requirementFromDirectoryName(File capabilityDirectory) {
        String shortName = humanReadableVersionOf(capabilityDirectory.getName());
        List<Requirement> children = readChildrenFrom(capabilityDirectory);
        return Requirement.named(shortName).withType(getDefaultType()).withNarrativeText(shortName).withChildren(children);
    }

    private Requirement requirementWithNarrative(File capabilityDirectory, Optional<Narrative> capabilityNarrative) {
        String shortName = humanReadableVersionOf(capabilityDirectory.getName());
        String displayName = getTitleFromNarrativeOrDirectoryName(capabilityNarrative.get(), capabilityDirectory);
        String cardNumber = capabilityNarrative.get().getCardNumber().orNull();
        String type = capabilityNarrative.get().getType();
        List<Requirement> children = readChildrenFrom(capabilityDirectory);
        return Requirement.named(shortName)
                          .withOptionalDisplayName(displayName)
                          .withOptionalCardNumber(cardNumber)
                          .withType(type)
                          .withNarrativeText(capabilityNarrative.get().getText())
                          .withChildren(children);
    }

    private String getDefaultType() {
        List<String> types = getCapabilityTypes();
        if (level > types.size() - 1) {
            return types.get(types.size() - 1);
        } else {
            return types.get(level);
        }
    }

    private List<String> getCapabilityTypes() {
        String capabilityTypes = ThucydidesSystemProperty.CAPABILITY_TYPES.from(environmentVariables);
        if (StringUtils.isNotEmpty(capabilityTypes)) {
            Iterator<String> types = Splitter.on(",").trimResults().split(capabilityTypes).iterator();
            return Lists.newArrayList(types);
        } else {
            return DEFAULT_CAPABILITY_TYPES;
        }
    }

    private List<Requirement> readChildrenFrom(File capabilityDirectory) {
        String childDirectory = rootDirectoryPath + "/" + capabilityDirectory.getName();
        RequirementsTagProvider childReader = new FileSystemRequirementsTagProvider(childDirectory, level + 1, environmentVariables);
        return childReader.getRequirements();
    }

    private String getTitleFromNarrativeOrDirectoryName(Narrative capabilityNarrative, File capabilityDirectory) {
        if (capabilityNarrative.getTitle().isPresent()) {
            return capabilityNarrative.getTitle().get();
        } else {
            return humanReadableVersionOf(capabilityDirectory.getName());
        }
    }

    private String humanReadableVersionOf(String name) {
        return Inflector.getInstance().humanize(name);
    }

    private FileFilter thatAreDirectories() {
        return new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory();
            }
        };
    }

}