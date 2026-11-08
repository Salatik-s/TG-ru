load("@build_bazel_rules_swift//swift:swift.bzl", "swift_library")

swift_library(
    name = "FamilyTransport",
    module_name = "FamilyTransport",
    srcs = glob(["Sources/Core/*.swift", "Sources/Apple/*.swift"]),
    deps = ["//submodules/MtProtoKit:MtProtoKit"],
    visibility = ["//visibility:public"],
)
