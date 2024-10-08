# Copyright 2021 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@rules_java//java:defs.bzl", "java_library")

licenses(["notice"])

package(default_visibility = ["//visibility:public"])

JAVACOPTS = [
    "-Xlint:unchecked",
]

java_library(
    name = "action",
    srcs = [
        "Action.java",
        "ActionContext.java",
        "ActionResult.java",
        "StarlarkAction.java",
    ],
    javacopts = JAVACOPTS,
    plugins = ["//java/com/google/copybara/doc:document"],
    deps = [
        "//java/com/google/copybara:base",
        "//java/com/google/copybara/config:base",
        "//java/com/google/copybara/effect",
        "//java/com/google/copybara/exception",
        "//java/com/google/copybara/revision",
        "//java/com/google/copybara/transform",
        "//java/com/google/copybara/util/console",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:starlark",
    ],
)
