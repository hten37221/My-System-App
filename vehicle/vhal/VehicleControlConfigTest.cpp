/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <JsonConfigLoader.h>
#include <android-base/file.h>
#include <gtest/gtest.h>

#include <array>
#include <cstdint>
#include <fstream>
#include <optional>
#include <string>
#include <vector>

namespace android::hardware::automotive::vehicle::test {
namespace {

using aidl::android::hardware::automotive::vehicle::VehiclePropertyAccess;
using aidl::android::hardware::automotive::vehicle::VehiclePropertyChangeMode;

constexpr char kConfigFile[] = "MySystemAppVehicleControlProperties.json";
constexpr int32_t kTypeMask = 0x00ff0000;
constexpr int32_t kBooleanType = 0x00200000;
constexpr int32_t kInt32Type = 0x00400000;

struct ExpectedProperty {
    int32_t id;
    int32_t type;
    int32_t defaultValue;
    std::optional<int32_t> minValue;
    std::optional<int32_t> maxValue;
    std::vector<int64_t> enumValues;
};

const std::array<ExpectedProperty, 13> kExpectedProperties = {{
        {0x21406000, kInt32Type, 1, 0, 2, {0, 1, 2}},
        {0x21406001, kInt32Type, 1, 0, 2, {0, 1, 2}},
        {0x21406002, kInt32Type, 1, 0, 3, {0, 1, 2, 3}},
        {0x21406003, kInt32Type, 1, 0, 2, {0, 1, 2}},
        {0x21406004, kInt32Type, 1, 0, 2, {0, 1, 2}},
        {0x21206005, kBooleanType, 1, std::nullopt, std::nullopt, {}},
        {0x21406006, kInt32Type, 0, -10, 10, {}},
        {0x21406007, kInt32Type, 5, 0, 10, {}},
        {0x21206008, kBooleanType, 1, std::nullopt, std::nullopt, {}},
        {0x21206009, kBooleanType, 1, std::nullopt, std::nullopt, {}},
        {0x2140600a, kInt32Type, 5, 0, 10, {}},
        {0x2120600b, kBooleanType, 1, std::nullopt, std::nullopt, {}},
        {0x2140600c, kInt32Type, 1, 0, 3, {0, 1, 2, 3}},
}};

TEST(MySystemAppVehicleControlVhalConfigTest, ParsesCompleteContract) {
    const std::string path = android::base::GetExecutableDirectory() + "/" + kConfigFile;
    std::ifstream input(path);
    ASSERT_TRUE(input.good()) << "Could not open config at " << path;

    JsonConfigLoader loader;
    auto result = loader.loadPropConfig(input);
    ASSERT_TRUE(result.ok()) << result.error().message();

    const auto& declarations = result.value();
    ASSERT_EQ(declarations.size(), kExpectedProperties.size());

    for (const auto& expected : kExpectedProperties) {
        const auto declarationIt = declarations.find(expected.id);
        ASSERT_NE(declarationIt, declarations.end()) << std::hex << expected.id;

        const auto& declaration = declarationIt->second;
        const auto& config = declaration.config;
        EXPECT_EQ(config.prop, expected.id);
        EXPECT_EQ(config.prop & kTypeMask, expected.type);
        EXPECT_EQ(config.access, VehiclePropertyAccess::READ_WRITE);
        EXPECT_EQ(config.changeMode, VehiclePropertyChangeMode::ON_CHANGE);
        EXPECT_FALSE(config.configString.empty());
        ASSERT_EQ(config.areaConfigs.size(), 1u);
        EXPECT_EQ(config.areaConfigs[0].areaId, 0);
        EXPECT_EQ(config.areaConfigs[0].access, VehiclePropertyAccess::READ_WRITE);

        ASSERT_EQ(declaration.initialValue.int32Values.size(), 1u);
        EXPECT_EQ(declaration.initialValue.int32Values[0], expected.defaultValue);

        if (expected.minValue.has_value()) {
            EXPECT_EQ(config.areaConfigs[0].minInt32Value, *expected.minValue);
            EXPECT_EQ(config.areaConfigs[0].maxInt32Value, *expected.maxValue);
            ASSERT_TRUE(config.areaConfigs[0].hasSupportedValueInfo.has_value());
            EXPECT_TRUE(config.areaConfigs[0].hasSupportedValueInfo->hasMinSupportedValue);
            EXPECT_TRUE(config.areaConfigs[0].hasSupportedValueInfo->hasMaxSupportedValue);
        } else {
            EXPECT_FALSE(config.areaConfigs[0].hasSupportedValueInfo.has_value());
        }

        if (!expected.enumValues.empty()) {
            ASSERT_TRUE(config.areaConfigs[0].supportedEnumValues.has_value());
            EXPECT_EQ(*config.areaConfigs[0].supportedEnumValues, expected.enumValues);
            EXPECT_TRUE(config.areaConfigs[0].hasSupportedValueInfo->hasSupportedValuesList);
        } else {
            EXPECT_FALSE(config.areaConfigs[0].supportedEnumValues.has_value());
        }
    }
}

}  // namespace
}  // namespace android::hardware::automotive::vehicle::test
