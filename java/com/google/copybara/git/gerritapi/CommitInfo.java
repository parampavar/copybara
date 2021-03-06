/*
 * Copyright (C) 2016 Google Inc.
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

package com.google.copybara.git.gerritapi;

import com.google.api.client.util.Key;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.StarlarkBuiltin;
import com.google.devtools.build.lib.skylarkinterface.StarlarkDocumentationCategory;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Sequence;
import com.google.devtools.build.lib.syntax.StarlarkList;
import com.google.devtools.build.lib.syntax.StarlarkValue;
import java.util.List;

/** https://gerrit-review.googlesource.com/Documentation/rest-api-changes.html#commit-info */
@SuppressWarnings("unused")
@StarlarkBuiltin(
    name = "gerritapi.CommitInfo",
    category = StarlarkDocumentationCategory.TOP_LEVEL_TYPE,
    doc = "Gerrit commit information.")
public class CommitInfo implements StarlarkValue {
  @Key private String commit;
  @Key private List<ParentCommitInfo> parents;
  @Key private GitPersonInfo author;
  @Key private GitPersonInfo committer;
  @Key private String subject;
  @Key private String message;

  @SkylarkCallable(
      name = "commit",
      doc =
          "The commit ID. Not set if included in a RevisionInfo entity that is contained "
              + "in a map which has the commit ID as key.",
      structField = true,
      allowReturnNones = true)
  public String getCommit() {
    return commit;
  }

  public List<ParentCommitInfo> getParents() {
    return parents == null ? ImmutableList.of() : ImmutableList.copyOf(parents);
  }

  @SkylarkCallable(
      name = "parents",
      doc =
          "The parent commits of this commit as a list of CommitInfo entities. "
              + "In each parent only the commit and subject fields are populated.",
      structField = true)
  public Sequence<ParentCommitInfo> getMessagesForSkylark() {
    return StarlarkList.immutableCopyOf(getParents());
  }

  @SkylarkCallable(
      name = "author",
      doc = "The author of the commit as a GitPersonInfo entity.",
      structField = true,
      allowReturnNones = true)
  public GitPersonInfo getAuthor() {
    return author;
  }

  @SkylarkCallable(
      name = "committer",
      doc = "The committer of the commit as a GitPersonInfo entity.",
      structField = true,
      allowReturnNones = true)
  public GitPersonInfo getCommitter() {
    return committer;
  }

  @SkylarkCallable(
      name = "subject",
      doc = "The subject of the commit (header line of the commit message).",
      structField = true,
      allowReturnNones = true)
  public String getSubject() {
    return subject;
  }

  @SkylarkCallable(
      name = "message",
      doc = "The commit message.",
      structField = true,
      allowReturnNones = true)
  public String getMessage() {
    return message;
  }

  @Override
  public void repr(Printer printer) {
    printer.append(toString());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("commit", commit)
        .add("parents", parents)
        .add("author", author)
        .add("committer", committer)
        .add("subject", subject)
        .add("message", message)
        .toString();
  }
}
