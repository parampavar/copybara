/*
 * Copyright (C) 2026 Google LLC
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

package com.google.copybara.git.github.api;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;

/** POJO representing the response from GitHubGraphQLApi getCheckRuns query. */
public class GetFilteredCheckRunsResponse {
  @Key private Data data;

  public Data getData() {
    return data;
  }

  /** Holds the data objects */
  public static class Data {
    @Key private Repository repository;

    public Repository getRepository() {
      return repository;
    }
  }

  /** Holds repository data */
  public static class Repository {
    @Key private CommitObject object;

    public CommitObject getObject() {
      return object;
    }
  }

  /** Holds commit object details */
  public static class CommitObject {
    @Key private CheckSuites checkSuites;
    @Key private AssociatedPullRequests associatedPullRequests;

    public CheckSuites getCheckSuites() {
      return checkSuites;
    }

    public AssociatedPullRequests getAssociatedPullRequests() {
      return associatedPullRequests;
    }
  }

  /** Wrapper for associated pull requests */
  public static class AssociatedPullRequests {
    @Key private List<PullRequestNode> nodes;

    public List<PullRequestNode> getNodes() {
      return nodes;
    }
  }

  /** Pull request node wrapper returning number */
  public static class PullRequestNode {
    @Key private int number;

    public int getNumber() {
      return number;
    }
  }

  /** Holds list of check suites */
  public static class CheckSuites {
    @Key private List<CheckSuiteNode> nodes;
    @Key private PageInfo pageInfo;

    public List<CheckSuiteNode> getNodes() {
      return nodes;
    }

    public PageInfo getPageInfo() {
      return pageInfo;
    }
  }

  /** Holds pagination information */
  public static class PageInfo {
    @Key private boolean hasNextPage;
    @Key private String endCursor;

    public boolean hasNextPage() {
      return hasNextPage;
    }

    public String getEndCursor() {
      return endCursor;
    }
  }

  /** Holds check suite node. Extends GenericJson to capture dynamically aliased checkRuns filters. */
  public static class CheckSuiteNode extends GenericJson {
    @Key private String id;

    public String getId() {
      return id;
    }

    public List<CheckRunNode> getCheckRuns() {
      ImmutableList.Builder<CheckRunNode> runs = ImmutableList.builder();
      for (Object value : this.values()) {
        if (value instanceof Map) {
          Map<?, ?> connectionMap = (Map<?, ?>) value;
          Object nodesObject = connectionMap.get("nodes");
          if (nodesObject instanceof List) {
            List<?> nodesList = (List<?>) nodesObject;
            for (Object node : nodesList) {
              if (node instanceof Map) {
                runs.add(new CheckRunNode((Map<?, ?>) node));
              }
            }
          }
        }
      }
      return runs.build();
    }
  }

  /** Node structure for check run details. */
  public static class CheckRunNode {
    private final String id;
    private final String name;
    private final String status;
    private final String conclusion;
    private final String detailsUrl;
    private final CheckSuiteDetails checkSuite;

    public CheckRunNode(Map<?, ?> map) {
      this.id = (String) map.get("id");
      this.name = (String) map.get("name");
      this.status = (String) map.get("status");
      this.conclusion = (String) map.get("conclusion");
      this.detailsUrl = (String) map.get("detailsUrl");
      Map<?, ?> suiteMap = (Map<?, ?>) map.get("checkSuite");
      this.checkSuite = suiteMap != null ? new CheckSuiteDetails(suiteMap) : null;
    }

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public String getStatus() {
      return status;
    }

    public String getConclusion() {
      return conclusion;
    }

    public String getDetailsUrl() {
      return detailsUrl;
    }

    public CheckSuiteDetails getCheckSuite() {
      return checkSuite;
    }
  }

  /** Holds check suite details. */
  public static class CheckSuiteDetails {
    private final CommitSha commit;
    private final AppDetails app;

    public CheckSuiteDetails(Map<?, ?> map) {
      Map<?, ?> commitMap = (Map<?, ?>) map.get("commit");
      this.commit = commitMap != null ? new CommitSha(commitMap) : null;
      Map<?, ?> appMap = (Map<?, ?>) map.get("app");
      this.app = appMap != null ? new AppDetails(appMap) : null;
    }

    public CommitSha getCommit() {
      return commit;
    }

    public AppDetails getApp() {
      return app;
    }
  }

  /** Commit SHA wrapper. */
  public static class CommitSha {
    private final String oid;

    public CommitSha(Map<?, ?> map) {
      this.oid = (String) map.get("oid");
    }

    public String getOid() {
      return oid;
    }
  }

  /** GitHub App metadata nested in check suite. */
  public static class AppDetails {
    private final Integer databaseId;
    private final String name;
    private final String slug;

    public AppDetails(Map<?, ?> map) {
      Number dbId = (Number) map.get("databaseId");
      this.databaseId = dbId != null ? dbId.intValue() : null;
      this.name = (String) map.get("name");
      this.slug = (String) map.get("slug");
    }

    public Integer getDatabaseId() {
      return databaseId;
    }

    public String getName() {
      return name;
    }

    public String getSlug() {
      return slug;
    }
  }
}