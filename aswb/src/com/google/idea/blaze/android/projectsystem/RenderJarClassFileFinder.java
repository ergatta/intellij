/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.projectsystem;

import com.android.tools.idea.projectsystem.ClassFileFinder;
import com.android.tools.idea.projectsystem.ClassFileFinderUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.android.libraries.RenderJarCache;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.android.sync.model.idea.BlazeClassJarProvider;
import com.google.idea.blaze.android.targetmaps.TargetToBinaryMap;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.io.VirtualFileSystemProvider;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.qsync.QuerySync;
import com.google.idea.blaze.base.sync.BlazeSyncModificationTracker;
import com.google.idea.blaze.base.sync.data.BlazeDataStorage;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.ProjectScope;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;

/**
 * A {@link ClassFileFinder} that uses deploy JAR like artifacts (called render jar henceforth) for
 * class files.
 *
 * <p>The render JAR contains all runtime dependencies of a binary target.
 *
 * <p>The Blaze targets that go into creating a resource module is known. Consequently, it is
 * possible to determine which binaries in the projectview depend on the resource declaring blaze
 * targets that constitutes the module. This class calculates the binary targets and attempts to
 * find classes from the render JARs.
 *
 * <p>This only works for resource modules (i.e. not the .workspace module). For .workspace module,
 * we try to find the class in all binary targets in projectview
 *
 * <p>NOTE: Blaze targets that constitutes the resource module will be called "resource target(s)"
 * in comments below.
 *
 * TODO: The role of this class has expanded beyond just render jar resolution. Should rename it.
 */
public class RenderJarClassFileFinder implements ClassFileFinder {
  /** Experiment to control whether class file finding from render jars should be enabled. */
  private static final BoolExperiment enabled =
      new BoolExperiment("aswb.renderjar.cff.enabled.3", true);

  /**
   * Experiment to toggle whether resource resolution is allowed from Render JARs. Render JARs
   * should not resolve resources by default, but the full ClassLoader mechanics of the preview
   * screen require that all classes resolve in order to display the content, so we allow it.
   */
  @VisibleForTesting
  static final BoolExperiment resolveResourceClasses =
      new BoolExperiment("aswb.resolve.resources.render.jar", false);

  private static final Logger log = Logger.getInstance(RenderJarClassFileFinder.class);

  private static final String INTERNAL_PACKAGE = "_layoutlib_._internal_.";

  // matches foo.bar.R or foo.bar.R$baz
  private static final Pattern RESOURCE_CLASS_NAME = Pattern.compile(".+\\.R(\\$[^.]+)?$");

  private final Module module;
  private final Project project;


  // tracks the binary targets that depend resource targets
  // will be recalculated after every sync
  private ImmutableSet<TargetKey> binaryTargets = ImmutableSet.of();

  // tracks the value of {@link BlazeSyncModificationTracker} when binaryTargets is calculated
  // binaryTargets is calculated when the value of {@link BlazeSyncModificationTracker} does not
  // equal lastSyncCount
  long lastSyncCount = -1;

  // true if the current module is the .workspace Module
  private final boolean isWorkspaceModule;

  private Map<String, File> packageJarHint = new HashMap();

  public RenderJarClassFileFinder(Module module) {
    this.module = module;
    this.project = module.getProject();
    this.isWorkspaceModule = BlazeDataStorage.WORKSPACE_MODULE_NAME.equals(module.getName());
    clearCache();
  }

  @Nullable
  @Override
  public VirtualFile findClassFile(String fqcn) {
    if (!isEnabled()) {
      return null;
    }

    // TODO(b/266726517): Query sync does not support render jars.
    if (QuerySync.isEnabled()) {
      return null;
    }

    // Ever since Compose support was introduced in AS, finding class files is invoked during the
    // normal course of opening an editor. The contract for this method requires that it shouldn't
    // throw any exceptions, but we've had a few bugs where this method threw an exception, which
    // resulted in users not being able to open Kotlin files at all. In order to avoid this
    // scenario, we wrap the underlying call and ensure that no exceptions are thrown.
    try {
      return findClass(fqcn);
    } catch (Error e) {
      log.warn(
          String.format(
              "Unexpected error while finding the class file for `%1$s`: %2$s",
              fqcn, Throwables.getRootCause(e).getMessage()));
      return null;
    }
  }

  @Nullable
  public VirtualFile findClass(String fqcn) {
    // Render JAR should not resolve any resources. All resources should be available to the IDE
    // through ResourceRepository. Attempting to resolve resources from Render JAR indicates that
    // ASwB hasn't properly set up resources for the project.
    if (isResourceClass(fqcn) && !resolveResourceClasses.getValue()) {
      log.warn(String.format("Attempting to load resource '%s' from RenderJAR.", fqcn));
      return null;
    }

    BlazeProjectData projectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (projectData == null) {
      log.warn("Could not find BlazeProjectData for project " + project.getName());
      return null;
    }

    ImmutableSet<TargetKey> binaryTargets = getBinaryTargets();
    if (binaryTargets.isEmpty()) {
      log.warn(
          String.format(
              "No binaries for module %s. Adding a binary target to the projectview and resyncing"
                  + " might fix the issue.",
              module.getName()));
      return null;
    }

    // Remove internal package prefix if present
    fqcn = StringUtil.trimStart(fqcn, INTERNAL_PACKAGE);

    // Look through render resolve JARs of the binaries that depend on the given
    // androidResourceModule. One androidResourceModule can comprise of multiple resource targets.
    // The binaries can depend on any subset of these resource targets. Generally, we only
    // expect one, or a small number of binaries here.
    for (TargetKey binaryTarget : binaryTargets) {
      VirtualFile classFile = getClassFromRenderResolveJar(projectData, fqcn, binaryTarget);
      if (classFile != null) {
        log.warn(String.format("Found class %s in target %s", fqcn, binaryTarget.toString()));
        return classFile;
      }
    }

    VirtualFile moduleClass = searchForFQCNInModule(fqcn);
    if (moduleClass != null) {
      return moduleClass;
    }

    // Fall back to workspace resolution
    if (!isWorkspaceModule) {
      Module workspaceModule = ModuleManager.getInstance(project)
          .findModuleByName(BlazeDataStorage.WORKSPACE_MODULE_NAME);
      return BlazeModuleSystem.getInstance(workspaceModule).classFileFinder.findClass(fqcn);
    }

    log.warn(String.format("Could not find class `%1$s` (module: `%2$s`)", fqcn, module.getName()));
    return null;
  }

  public synchronized void clearCache() {
    log.warn("clearing cache");
    packageJarHint = new HashMap();
  }

  @VisibleForTesting
  static boolean isResourceClass(String fqcn) {
    return RESOURCE_CLASS_NAME.matcher(fqcn).matches();
  }

  /**
   * Returns the cached list of binary targets that depend on resource targets. The cache is
   * recalculated if the project has been synced since last calculation
   */
  private ImmutableSet<TargetKey> getBinaryTargets() {
    long currentSyncCount =
        BlazeSyncModificationTracker.getInstance(project).getModificationCount();
    if (currentSyncCount == lastSyncCount) {
      // Return the cached set if there hasn't been a sync since last calculation
      return binaryTargets;
    }
    lastSyncCount = currentSyncCount;

    AndroidResourceModule androidResourceModule =
        AndroidResourceModuleRegistry.getInstance(project).get(module);
    if (androidResourceModule != null) {
      binaryTargets =
          TargetToBinaryMap.getInstance(project)
              .getBinariesDependingOn(androidResourceModule.sourceTargetKeys);
    } else if (isWorkspaceModule) {
      binaryTargets = TargetToBinaryMap.getInstance(project).getSourceBinaryTargets();
    } else {
      binaryTargets = ImmutableSet.of();
      log.warn("Could not find AndroidResourceModule for " + module.getName());
    }
    log.info(
        String.format(
            "Binary targets for module `%1$s`: %2$s",
            module.getName(),
            binaryTargets.stream()
                .limit(5)
                .map(t -> t.getLabel().toString())
                .collect(joining(", "))));
    return binaryTargets;
  }

  /**
   * Returns class file for fqcn if found in the render JAR corresponding to {@code binaryTarget}.
   * Returns null if something goes wrong or if render JAR does not contain fqcn
   */
  @Nullable
  private VirtualFile getClassFromRenderResolveJar(
      BlazeProjectData projectData, String fqcn, TargetKey binaryTarget) {
    TargetIdeInfo ideInfo = projectData.getTargetMap().get(binaryTarget);
    if (ideInfo == null) {
      return null;
    }

    File renderResolveJarFile =
        RenderJarCache.getInstance(project)
            .getCachedJarForBinaryTarget(projectData.getArtifactLocationDecoder(), ideInfo);

    if (renderResolveJarFile == null) {
      return null;
    }

    VirtualFile renderResolveJarVF =
        VirtualFileSystemProvider.getInstance().getSystem().findFileByIoFile(renderResolveJarFile);
    if (renderResolveJarVF == null) {
      return null;
    }

    return findClassInJar(renderResolveJarVF, fqcn);
  }

  @Nullable
  private VirtualFile searchForFQCNInModule(String fqcn) {
    // keeps throwing java.lang.Throwable: Slow operations are prohibited on EDT. See SlowOperations.assertSlowOperationsAreAllowed javadoc
    /*VirtualFile psiFile = ApplicationManager.getApplication().runReadAction((Computable<VirtualFile>) () -> {
      try {
        final JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass baseClass =
                facade.findClass(fqcn, ProjectScope.getAllScope(project));
        if (baseClass == null) {
          return null;
        }
        VirtualFile theFile = baseClass.getNavigationElement().getContainingFile().getVirtualFile();
        if (theFile.toString().endsWith(".class")) {
          return theFile;
        }
      } catch (Throwable t) {
        log.warn("failed to use JavaPsiFacade: " + t.getLocalizedMessage());
      }
      return null;
    });

    if (psiFile != null) {
      return psiFile;
    }*/

    String pkg = null;
    int pkgIdx = fqcn.lastIndexOf('.');
    if (pkgIdx != -1) {
      pkg = fqcn.substring(0, pkgIdx);
    }
    File hintJar = pkg == null ? null : packageJarHint.get(pkg);
    if (hintJar != null) {
      VirtualFile jarVF = VirtualFileSystemProvider.getInstance().getSystem().findFileByIoFile(hintJar);
      if (jarVF != null) {
        VirtualFile foundClass = findClassInJar(jarVF, fqcn);
        if (foundClass != null) {
          return foundClass;
        }
      }
    }
    List<File> moduleLibraries = new BlazeClassJarProvider(this.project).getModuleExternalLibraries(module);

    for (File jar : moduleLibraries) {
      VirtualFile jarVF = VirtualFileSystemProvider.getInstance().getSystem().findFileByIoFile(jar);
      if (jarVF == null) {
        continue;
      }
      VirtualFile foundClass = findClassInJar(jarVF, fqcn);
      if (foundClass != null) {
        if (pkg != null) {
          packageJarHint.put(pkg, jar);
        }
        return foundClass;
      }
    }
    return null;
  }

  @Nullable
  private static VirtualFile findClassInJar(final VirtualFile classJar, String fqcn) {
    VirtualFile jarRoot = getJarRootForLocalFile(classJar);
    if (jarRoot == null) {
      return null;
    }
    return ClassFileFinderUtil.findClassFileInOutputRoot(jarRoot, fqcn);
  }

  /** Test aware method to redirect JARs to {@link VirtualFileSystemProvider} for tests */
  private static VirtualFile getJarRootForLocalFile(VirtualFile file) {
    return ApplicationManager.getApplication().isUnitTestMode()
        ? VirtualFileSystemProvider.getInstance()
            .getSystem()
            .findFileByPath(file.getPath() + JarFileSystem.JAR_SEPARATOR)
        : JarFileSystem.getInstance().getJarRootForLocalFile(file);
  }

  public static boolean isEnabled() {
    return enabled.getValue();
  }
}
