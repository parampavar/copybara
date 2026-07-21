/*
 * Copyright (C) 2023 Google LLC
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


import com.google.api.client.util.Key;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.profiler.Profiler;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** GraphQL implementation for GitHub client */
public class GitHubGraphQLApi {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int CHECK_RUNS_MAX_PAGE_SIZE = 10;

  /** GraphQL request body */
  public static class GraphQLRequest {
    @Key("query")
    private String query;

    @Key("variables")
    private Map<String, Object> variables;

    public GraphQLRequest(String query, Map<String, Object> variables) {
      this.query = query;
      this.variables = variables;
    }

    public GraphQLRequest() {}

    public String getQuery() {
      return query;
    }

    public Map<String, Object> getVariables() {
      return variables;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("variables", variables)
          .add("query", query)
          .toString();
    }
  }

  private final GitHubApiTransport transport;
  private final Profiler profiler;

  public GitHubGraphQLApi(GitHubApiTransport transport, Profiler profiler) {
    this.transport = Preconditions.checkNotNull(transport);
    this.profiler = Preconditions.checkNotNull(profiler);
  }

  /** Sets GraphQL first parameters for the getCommitHistory call. */
  public static class GetCommitHistoryParams {
    private int commits;
    private int pullRequests;
    private int reviews;

    public GetCommitHistoryParams() {}

    public GetCommitHistoryParams(int commits, int pullRequests, int reviews) {
      this.commits = commits;
      this.pullRequests = pullRequests;
      this.reviews = reviews;
    }

    public int getCommits() {
      return commits;
    }

    public int getPullRequests() {
      return pullRequests;
    }

    public int getReviews() {
      return reviews;
    }

    public GetCommitHistoryParams getCopyWithCommits(int commits) {
      return new GetCommitHistoryParams(commits, this.pullRequests, this.reviews);
    }
  }

  public CommitHistoryResponse getCommitHistory(
      String org, String repo, String branch, GetCommitHistoryParams params)
      throws RepoException, ValidationException {
    ValidationException.checkCondition(
        !Strings.isNullOrEmpty(org)
            && !Strings.isNullOrEmpty(repo)
            && !Strings.isNullOrEmpty(branch),
        "Attempted to query for GitHub commit history, but received a empty/null value: org=%s,"
            + " repo=%s, branch=%s",
        org,
        repo,
        branch);
    // TODO(linjordan): this could look better with a query builder api or load from .graphql file.
   String getCommitHistoryQuery =
                            "query ($repoName: String!, $repoOwner:String!, $branch: String!,"
                              + "$numberOfCommits: Int, $numberOfPRs: Int, "
                              + "$numberOfReviews: Int) {\n"
                              + "repository(name: $repoName, owner: $repoOwner) {\n"
                              + "ref(qualifiedName: $branch) {\n"
                              +    "target {\n"
                              +      "... on Commit {\n"
                              +        "id\n"
                              +        "history(first: $numberOfCommits) {\n"
                              +          "nodes {\n"
                              +            "id\n"
                              +            "oid\n"
                              +            "associatedPullRequests(first: $numberOfPRs) {\n"
                              +              "edges {\n"
                              +                "node {\n"
                              +                  "title\n"
                              +                  "mergedBy {\n"
                              +                    "login\n"
                              +                  "}\n"
                              +                  "author {\n"
                              +                    "login\n"
                              +                  "}\n"
                              +                  "reviewDecision\n"
                              +                  "latestOpinionatedReviews(first: $numberOfReviews)"
                              +                  "{\n"
                              +                    "edges {\n"
                              +                      "node {\n"
                              +                        "author {\n"
                              +                          "login\n"
                              +                        "}\n"
                              +                        "state\n"
                              +                      "}\n"
                              +                    "}\n"
                              +                  "}\n"
                              +                "}\n"
                              +             "}\n"
                              +            "}\n"
                              +          "}\n"
                              +        "}\n"
                              +      "}\n"
                              +    "}\n"
                              +  "}\n"
                              + "}\n"
                          + "}\n";
    ImmutableMap<String, Object> variables =
        ImmutableMap.of(
            "repoOwner",
            org,
            "repoName",
            repo,
            "branch",
            branch,
            "numberOfCommits",
            params.getCommits(),
            "numberOfPRs",
            params.getPullRequests(),
            "numberOfReviews",
            params.getReviews());
    try (ProfilerTask ignore = profiler.start("github_api_get_commit_history")) {
      return transport.post(
          "/graphql",
          new GraphQLRequest(getCommitHistoryQuery, variables),
          CommitHistoryResponse.class,
          "POST GraphQL");
    }
  }

  public ImmutableList<CheckRun> getCheckRunsByNameFilter(
      String owner, String repo, String sha, Set<String> checkNames)
      throws RepoException, ValidationException {
    if (checkNames.isEmpty()) {
      return ImmutableList.of();
    }

    // generate filters for each check name
    ImmutableList.Builder<String> checkRunFilters = ImmutableList.builder();
    int idx = 0;
    for (String checkName : checkNames) {
      checkRunFilters.add(
          String.format(
              """
                  filter_%s: checkRuns(first: 100, filterBy: {checkName: "%s"}) {
                    nodes {
                      id
                      name
                      status
                      conclusion
                      detailsUrl
                      checkSuite {
                        commit {
                          oid
                        }
                        app {
                          databaseId
                          name
                          slug
                        }
                      }
                    }
                  }
              """,
              idx++, checkName));
    }

    String query =
        String.format(
            """
                query ($owner: String!, $repo: String!, $sha: String!, $suiteCursor: String) {
                  repository(owner: $owner, name: $repo) {
                    object(expression: $sha) {
                      ... on Commit {
                        associatedPullRequests(first: 1) {
                          nodes {
                            number
                          }
                        }
                        checkSuites(first: 100, after: $suiteCursor) {
                          pageInfo {
                            hasNextPage
                            endCursor
                          }
                          nodes {
                            %s
                          }
                        }
                      }
                    }
                  }
                }
            """,
            String.join("\n", checkRunFilters.build()));

    ImmutableList.Builder<CheckRun> checkRuns = ImmutableList.builder();
    boolean hasNextPage = true;
    String cursor = null;
    int pagesQueried = 0;
    while (hasNextPage && pagesQueried < CHECK_RUNS_MAX_PAGE_SIZE) {
      try (ProfilerTask ignore = profiler.start("github_api_get_check_runs_history")) {
        ImmutableMap.Builder<String, Object> variables =
            ImmutableMap.<String, Object>builder()
                .put("owner", owner)
                .put("repo", repo)
                .put("sha", sha);
        if (cursor != null) {
          variables.put("suiteCursor", cursor);
        }
        ImmutableMap<String, Object> variablesMap = variables.buildOrThrow();

        logger.atInfo().log("Querying check runs with variables: %s", variablesMap);
        GetFilteredCheckRunsResponse response =
            transport.post(
                "/graphql",
                new GraphQLRequest(query, variablesMap),
                GetFilteredCheckRunsResponse.class,
                "POST GraphQL");
        pagesQueried++;

        if (response == null
            || response.getData() == null
            || response.getData().getRepository() == null
            || response.getData().getRepository().getObject() == null) {
          logger.atInfo().log(
              "Response was unexpectedly null for getCheckRunsByNameFilter(owner: %s, repo: %s,"
                  + " sha: %s, checkNames: %s)",
              owner, repo, sha, checkNames);
          break;
        }

        checkRuns.addAll(getCheckRunsFromResponse(response, sha));

        // Update cursor for next page of check suites.
        GetFilteredCheckRunsResponse.CommitObject commitObj = response.getData().getRepository().getObject();
        GetFilteredCheckRunsResponse.CheckSuites checkSuites = commitObj.getCheckSuites();
        if (checkSuites == null) {
          break;
        }
        GetFilteredCheckRunsResponse.PageInfo pageInfo = checkSuites.getPageInfo();
        if (pageInfo != null && pageInfo.hasNextPage()) {
          cursor = pageInfo.getEndCursor();
        } else {
          hasNextPage = false;
        }
      }
    }

    return checkRuns.build();
  }

  private ImmutableList<CheckRun> getCheckRunsFromResponse(
      GetFilteredCheckRunsResponse response, String sha) {
    if (response == null
        || response.getData() == null
        || response.getData().getRepository() == null
        || response.getData().getRepository().getObject() == null) {
      return ImmutableList.of();
    }

    GetFilteredCheckRunsResponse.CommitObject commitObj = response.getData().getRepository().getObject();
    ImmutableList<CheckRun.PullRequest> pullRequests = ImmutableList.of();
    if (commitObj.getAssociatedPullRequests() != null
        && commitObj.getAssociatedPullRequests().getNodes() != null) {
      ImmutableList.Builder<CheckRun.PullRequest> prsBuilder = ImmutableList.builder();
      for (GetFilteredCheckRunsResponse.PullRequestNode prNode :
          commitObj.getAssociatedPullRequests().getNodes()) {
        prsBuilder.add(new CheckRun.PullRequest(prNode.getNumber()));
      }
      pullRequests = prsBuilder.build();
    }

    if (commitObj.getCheckSuites() == null) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<CheckRun> checkRuns = ImmutableList.builder();
    GetFilteredCheckRunsResponse.CheckSuites checkSuites = commitObj.getCheckSuites();
    if (checkSuites.getNodes() != null) {
      for (GetFilteredCheckRunsResponse.CheckSuiteNode suiteNode : checkSuites.getNodes()) {
        for (GetFilteredCheckRunsResponse.CheckRunNode runNode : suiteNode.getCheckRuns()) {
          checkRuns.add(convertToCheckRun(runNode, sha, pullRequests));
        }
      }
    }
    return checkRuns.build();
  }

  private CheckRun convertToCheckRun(
      GetFilteredCheckRunsResponse.CheckRunNode runNode,
      String commitSha,
      ImmutableList<CheckRun.PullRequest> pullRequests) {
    CheckRun.Status apiStatus =
        runNode.getStatus() == null
            ? CheckRun.Status.PENDING
            : CheckRun.Status.valueOf(runNode.getStatus().toUpperCase(Locale.US));

    CheckRun.Conclusion apiConclusion = null;
    if (runNode.getConclusion() != null) {
      String upperConclusion = runNode.getConclusion().toUpperCase(Locale.US).replace("_", "");
      try {
        apiConclusion = CheckRun.Conclusion.valueOf(upperConclusion);
      } catch (IllegalArgumentException e) {
        apiConclusion = CheckRun.Conclusion.NONE;
      }
    }

    GitHubApp apiApp = null;
    if (runNode.getCheckSuite() != null) {
      if (runNode.getCheckSuite().getCommit() != null) {
        GetFilteredCheckRunsResponse.CommitSha commit = runNode.getCheckSuite().getCommit();
        String oid = commit.getOid();
        if (oid != null) {
          commitSha = oid;
        }
      }
      if (runNode.getCheckSuite().getApp() != null) {
        GetFilteredCheckRunsResponse.AppDetails app = runNode.getCheckSuite().getApp();
        int appId = app.getDatabaseId() != null ? app.getDatabaseId() : 0;
        apiApp = new GitHubApp(appId, app.getSlug(), app.getName());
      }
    }

    return new CheckRun(
        runNode.getDetailsUrl(),
        apiStatus,
        apiConclusion,
        commitSha,
        runNode.getName(),
        apiApp,
        /* output= */ null,
        /* pullRequests= */ pullRequests);
  }
}
