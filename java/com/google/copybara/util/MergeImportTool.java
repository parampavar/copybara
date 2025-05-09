/*
 * Copyright (C) 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.util;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.LocalParallelizer;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.util.console.Console;
import com.google.protobuf.ByteString;
import com.google.re2j.Pattern;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A tool that assists with Merge Imports
 *
 * <p>Merge Imports allow users to persist changes in non-source-of-truth destinations. Destination
 * only changes are defined by files that exist in the destination workdir, but not in the origin
 * baseline or in the origin workdir.
 */
public final class MergeImportTool {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Console console;
  private final MergeRunner mergeRunner;
  private final int threadsForMergeImport;
  @Nullable
  private final Pattern debugMergeImport;
  private static final int THREADS_MIN_SIZE = 5;

  public MergeImportTool(
      Console console,
      MergeRunner mergeRunner,
      int threadsForMergeImport,
      @Nullable Pattern debugMergeImport) {
    this.console = console;
    this.mergeRunner = mergeRunner;
    this.threadsForMergeImport = threadsForMergeImport;
    this.debugMergeImport = debugMergeImport;
  }

  /**
   * A command that shells out to a diffing tool to merge files in the working directories
   *
   * <p>The origin is treated as the source of truth. Files that exist at baseline and destination
   * but not in the origin will be deleted. Files that exist in the destination but not in the
   * origin or in the baseline will be considered "destination only" and propagated.
   *
   * @param originWorkdir The working directory for the origin repository, already populated by the
   *     caller
   * @param destinationWorkdir A copy of the destination repository state, already populated by the
   *     caller
   * @param baselineWorkdir A copy of the baseline repository state, already populated by the caller
   * @param diffToolWorkdir A working directory for the CommandLineDiffUtil
   * @return a list of paths that resulted in merge errors.
   */
  public ImmutableList<String> mergeImport(
      Path originWorkdir,
      Path destinationWorkdir,
      Path baselineWorkdir,
      Path diffToolWorkdir,
      Glob matcher,
      Path packagePath)
      throws IOException, ValidationException {
    HashSet<Path> visitedSet = new HashSet<>();
    HashSet<Path> mergeErrorPaths = new HashSet<>();
    HashSet<Path> troublePaths = new HashSet<>();
    HashSet<FilePathInformation> filesToProcess = new HashSet<>();
    maybeDebugFolder(originWorkdir, "current");
    maybeDebugFolder(baselineWorkdir, "baseline");
    maybeDebugFolder(destinationWorkdir, "destination");
    SimpleFileVisitor<Path> originWorkdirFileVisitor =
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isSymbolicLink()) {
              return FileVisitResult.CONTINUE;
            }
            Path relativeFile = originWorkdir.relativize(file);
            Path baselineFile = baselineWorkdir.resolve(relativeFile);
            Path destinationFile = destinationWorkdir.resolve(relativeFile);
            Path relativizedFile = packagePath.relativize(relativeFile);
            boolean match =
                matcher
                    .relativeTo(Paths.get(""))
                    .matches(Path.of("/".concat(relativizedFile.toString())));

            // All 3 files must be present to merge
            if (!Files.exists(destinationFile) || !Files.exists(baselineFile) || !match) {
              return FileVisitResult.CONTINUE;
            }
            // No internal-only modifications, no need to merge
            try {
              if (compareFileContents(destinationFile, baselineFile)) {
                return FileVisitResult.CONTINUE;
              }
            } catch (IOException e) {
              logger.atWarning().withCause(e).log(
                  "Cannot read one of (%s, %s) - will not attempt to merge",
                  baselineFile, destinationFile);
              return FileVisitResult.CONTINUE;
            }
            try {
              // same changes to both upstream and downstream, no need to merge
              if (compareFileContents(destinationFile, file)) {
                return FileVisitResult.CONTINUE;
              }
            } catch (IOException e) {
              logger.atWarning().withCause(e).log(
                  "Cannot read one of (%s, %s) - will not attempt to merge", file, destinationFile);
              return FileVisitResult.CONTINUE;
            }
            filesToProcess.add(
                FilePathInformation.create(file, relativeFile, baselineFile, destinationFile));

            return FileVisitResult.CONTINUE;
          }
        };

    SimpleFileVisitor<Path> destinationWorkdirFileVisitor =
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink()) {
              return FileVisitResult.CONTINUE;
            }
            Path relativeFile = destinationWorkdir.relativize(file);
            Path originFile = originWorkdir.resolve(relativeFile);
            Path baselineFile = baselineWorkdir.resolve(relativeFile);
            if (visitedSet.contains(relativeFile)) {
              return FileVisitResult.CONTINUE;
            }
            // destination only file - keep it
            if (!Files.exists(originFile) && !Files.exists(baselineFile)) {
              Files.createDirectories(originWorkdir.resolve(relativeFile).getParent());
              Files.copy(file, originWorkdir.resolve(relativeFile));
            }
            // file was deleted in origin, propagate to destination
            if (!Files.exists(originFile) && Files.exists(baselineFile)) {
              Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
          }
        };

    Files.walkFileTree(originWorkdir, originWorkdirFileVisitor);
    logger.atInfo().log("Using %d thread(s) for merging files", threadsForMergeImport);
    List<OperationResults> results =
        new LocalParallelizer(threadsForMergeImport, THREADS_MIN_SIZE).run(
            ImmutableSet.copyOf(filesToProcess), new BatchCaller(diffToolWorkdir));
    for (OperationResults result : results) {
      visitedSet.addAll(result.visitedFiles());
      mergeErrorPaths.addAll(result.mergeErrorPaths());
      troublePaths.addAll(result.troublePaths());
    }
    Files.walkFileTree(destinationWorkdir, destinationWorkdirFileVisitor);
    if (!mergeErrorPaths.isEmpty()) {
      mergeErrorPaths.forEach(
          path -> console.warn(String.format("Merge error for path %s", path.toString())));
    }
    if (!troublePaths.isEmpty()) {
      troublePaths.forEach(
          path ->
              console.warn(String.format("diff3 exited with code 2 for path %s, skipping", path)));
    }
    return mergeErrorPaths.stream().map(Path::toString).collect(toImmutableList());
  }

  /**
   * Debug the contents of the file that are used by diff3. We just show the SHA-1 of the file
   * instead of the content.
   */
  private void maybeDebugFolder(Path path, String name) throws IOException {
    if (debugMergeImport != null) {
      SimpleFileVisitor<Path> visitor =
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (!debugMergeImport.matches(file.toString())) {
                return FileVisitResult.CONTINUE;
              }
              if (attrs.isSymbolicLink()) {
                console.verboseFmt("MERGE_DEBUG %s %s: symlink", name, path.relativize(file));
                return FileVisitResult.CONTINUE;
              }
              try {
                console.verboseFmt(
                    "MERGE_DEBUG %s %s:\n%s",
                    name, path.relativize(file), Files.readString(file, UTF_8));
              } catch (IOException e) {
                logger.atWarning().withCause(e).log("Cannot hash file %s", file);
              }

              return FileVisitResult.CONTINUE;
            }
          };
      Files.walkFileTree(path, visitor);
    }
  }

  private class BatchCaller
      implements LocalParallelizer.TransformFunc<FilePathInformation, OperationResults> {

    private final Path diffToolWorkdir;

    BatchCaller(Path diffToolWorkdir) {
      this.diffToolWorkdir = diffToolWorkdir;
    }

    @Override
    public OperationResults run(Iterable<FilePathInformation> elements) throws IOException {
      HashSet<Path> visitedSet = new HashSet<>();
      HashSet<Path> mergeErrorPaths = new HashSet<>();
      HashSet<Path> troublePaths = new HashSet<>();

      for (FilePathInformation paths : elements) {
        Path file = paths.file();
        Path relativeFile = paths.relativeFile();
        Path baselineFile = paths.baselineFile();
        Path destinationFile = paths.destinationFile();
        if (!Files.exists(destinationFile) || !Files.exists(baselineFile)) {
          continue;
        }
        MergeResult output =
            mergeRunner.merge(file, destinationFile, baselineFile, diffToolWorkdir);
        visitedSet.add(relativeFile);
        if (output.result() == MergeResultCode.MERGE_CONFLICT) {
          mergeErrorPaths.add(file);
        }
        if (output.result() == MergeResultCode.TROUBLE) {
          troublePaths.add(file);
        }

        OutputStream outputStream = Files.newOutputStream(file);
        output.fileContents().writeTo(outputStream);
      }

      return OperationResults.create(
          ImmutableSet.copyOf(visitedSet),
          ImmutableSet.copyOf(mergeErrorPaths),
          ImmutableSet.copyOf(troublePaths));
    }
  }

  /** A class that will contain Path information for a file from the origin workdir. */
  @AutoValue
  abstract static class FilePathInformation {
    static FilePathInformation create(
        Path file, Path relativeFile, Path baselineFile, Path destinationFile) {
      return new AutoValue_MergeImportTool_FilePathInformation(
          file, relativeFile, baselineFile, destinationFile);
    }

    abstract Path file();

    abstract Path relativeFile();

    abstract Path baselineFile();

    abstract Path destinationFile();
  }

  /**
   * Contains the results of a batch job performed by LocalParallelizer, which includes the Paths of
   * which files were visited, and which files had merge errors reported by the external diffing
   * tool.
   */
  @AutoValue
  abstract static class OperationResults {
    static OperationResults create(
        ImmutableSet<Path> visitedFiles,
        ImmutableSet<Path> mergeErrorPaths,
        ImmutableSet<Path> troublePaths) {
      return new AutoValue_MergeImportTool_OperationResults(
          visitedFiles, mergeErrorPaths, troublePaths);
    }

    abstract ImmutableSet<Path> visitedFiles();

    abstract ImmutableSet<Path> mergeErrorPaths();

    abstract ImmutableSet<Path> troublePaths();
  }

  enum MergeResultCode {
    SUCCESS,
    MERGE_CONFLICT,
    TROUBLE
  }

  @AutoValue
  abstract static class MergeResult {
    static MergeResult create(ByteString fileContents, MergeResultCode resultCode) {
      return new AutoValue_MergeImportTool_MergeResult(fileContents, resultCode);
    }

    abstract ByteString fileContents();

    abstract MergeResultCode result();
  }

  /** MergeRunner is called by MergeImportTool to merge one file. */
  public interface MergeRunner {
    MergeResult merge(Path lhs, Path rhs, Path baseline, Path workdir) throws IOException;
  }

  private static boolean compareFileContents(Path file1, Path file2) throws IOException {
    try (BufferedInputStream inputStream =
            new BufferedInputStream(new FileInputStream(file1.toFile()));
        BufferedInputStream inputStream2 =
            new BufferedInputStream(new FileInputStream(file2.toFile()))) {

      int ptr;
      while ((ptr = inputStream.read()) != -1) {
        if (ptr != inputStream2.read()) {
          return false;
        }
      }
      // file2 has contents remaining
      if (inputStream2.read() != -1) {
        return false;
      }
    }
    return true;
  }
}
