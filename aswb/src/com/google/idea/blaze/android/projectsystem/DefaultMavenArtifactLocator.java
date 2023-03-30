package com.google.idea.blaze.android.projectsystem;

import com.android.ide.common.repository.GradleCoordinate;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.settings.BuildSystemName;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

public class DefaultMavenArtifactLocator implements MavenArtifactLocator {
    private static final Logger log = Logger.getInstance(DefaultMavenArtifactLocator.class);
    private static final String mavenCoordinateTagPrefix = "maven_coordinates=";

    /**
     * Locate an artifact label by maven coordinates. This is somewhat brittle,
     * but Android Studio requests specific artifacts needed for their preview
     * system, so we make our best attempt here to locate them using `maven_install`
     * patterned bazel artifacts.
     */
    public Label labelFor(Project project, GradleCoordinate coordinate) {
        TargetMap targetMap = BlazeProjectDataManager.getInstance(project)
            .getBlazeProjectData().getTargetMap();

        String desiredCoord = mavenCoordinateTagPrefix + coordinate.getGroupId() +
                ":" + coordinate.getArtifactId() + ":";
        String labelSuffix = String.format(":%s_%s",
            coordinate.getGroupId().replaceAll("[.-]", "_"),
            coordinate.getArtifactId().replaceAll("[.-]", "_")
        );

        // Debug code to list all targets. Some go missing sometimes...
        /*String debugString = targetMap.map().keySet().stream()
                .sorted(Comparator.comparing(TargetKey::toString))
                .map(x -> x.getLabel().toString())
                .collect(Collectors.joining(", "));
        System.out.println(debugString);*/

        return targetMap.targets().stream().filter(target -> {
            for (String tag : target.getTags()) {
                if (tag.startsWith(desiredCoord)) {
                    return true;
                }
            }
            return false;
        })
            .map(x -> x.getKey().getLabel())
            .findFirst().orElseGet(() -> {
                Label bestGuess = Label.create("@maven//" + labelSuffix);
                log.warn(String.format(
                        "Could not find exact label for %s, returning best guess of %s",
                        coordinate, bestGuess));
                return bestGuess;
            });
    }

    public BuildSystemName buildSystem() {
        return BuildSystemName.Bazel;
    }
}