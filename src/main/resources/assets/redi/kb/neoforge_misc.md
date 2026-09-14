# NeoForge 杂项与世界生成

> 来源:neoforged/Documentation 官方文档(英文原文,API 名与代码签名原样保留)。
> 回答时用中文解释,类名/方法名保持英文。

## misc/config

# Configuration

Configurations define settings and consumer preferences that can be applied to a mod instance. NeoForge uses a configuration system using [TOML][toml] files and read with [NightConfig][nightconfig].

## Creating a Configuration

A configuration can be created using a subtype of `IConfigSpec`. NeoForge implements the type via `ModConfigSpec` and enables its construction through `ModConfigSpec.Builder`. The builder can separate the config values into sections via `Builder#push` to create a section and `Builder#pop` to leave a section. Afterwards, the configuration can be built using one of two methods:

 Method     | Description
 :---       | :---
`build`     | Creates the `ModConfigSpec`.
`configure` | Creates a pair of the class holding the config values and the `ModConfigSpec`.

:::note
`ModConfigSpec.Builder#configure` is typically used with a `static` block and a class that takes in `ModConfigSpec.Builder` as part of its constructor to attach and hold the values:

```java
//Define a field to keep the config and spec for later
public static final ExampleConfig CONFIG;
public static final ModConfigSpec CONFIG_SPEC;

private ExampleConfig(ModConfigSpec.Builder builder) {
    // Define properties used by the configuration
    // ...
}

//CONFIG and CONFIG_SPEC are both built from the same builder, so we use a static block to seperate the properties
static {
    Pair<ExampleConfig, ModConfigSpec> pair =
            new ModConfigSpec.Builder().configure(ExampleConfig::new);
        
    //Store the resulting values
    CONFIG = pair.getLeft();
    CONFIG_SPEC = pair.getRight();
}
```
:::

Each config value can be supplied with additional context to provide additional behavior. Contexts must be defined before the config value is fully built:

| Method         | Description                                                                                                 |
|:---------------|:------------------------------------------------------------------------------------------------------------|
| `comment`      | Provides a description of what the config value does. Can provide multiple strings for a multiline comment. |
| `translation`  | Provides a translation key for the name of the config value.                                                |
| `worldRestart` | The world must be restarted before the config value can be changed.                                         |
| `gameRestart`  | The game must be restarted before the config value can be changed.                                          |

### ConfigValue

Config values can be built with the provided contexts (if defined) using any of the `#define` methods.

All config value methods take in at least two components:

- A path representing the name of the variable: a `.` separated string representing the sections the config value is in
- The default value when no valid configuration is present

The `ConfigValue` specific methods take in two additional components:

- A validator to make sure the deserialized object is valid
- A class representing the data type of the config value

```java
//Store the config properties as public finals
public final ModConfigSpec.ConfigValue<String> welcomeMessage;

private ExampleConfig(ModConfigSpec.Builder builder) {
    //Define each property
    //One property could be a message to log to the console when the game is initialised
    welcomeMessage = builder.define("welcome_message", "Hello from the config!");
}
```

The values themselves can be obtained using `ConfigValue#get`. The values are additionally cached to prevent multiple readings from files.

#### Additional Config Value Types

- **Range Values**
    - Description: Value must be between the defined bounds
    - Class Type: `Comparable<T>`
    - Method Name: `#defineInRange`
    - Additional Components:
        - The minimum and maximum the config value may be
        - A class representing the data type of the config value

:::note
`DoubleValue`s, `IntValue`s, and `LongValue`s are range values which specify the class as `Double`, `Integer`, and `Long` respectively.
:::

- **Whitelisted Values**
    - Description: Value must be in supplied collection
    - Class Type: `T`
    - Method Name: `#defineInList`
    - Additional Components:
        - A collection of the allowed values the configuration can be

- **List Values**
    - Description: Value is a list of entries
    - Class Type: `List<T>`
    - Method Name: `#defineList`, `#defineListAllowEmpty` if list can be empty
    - Additional Components:
        - A supplier that returns a default value to use when a new entry is added in configuration screens.
        - A validator to make sure a deserialized element from the list is valid
        - (optional) A vaidator to make sure the list does not get too little or too many entries

- **Enum Values**
    - Description: An enum value in the supplied collection
    - Class Type: `Enum<T>`
    - Method Name: `#defineEnum`
    - Additional Components:
        - A getter to convert a string or integer into an enum
        - A collection of the allowed values the configuration can be

- **Boolean Values**
    - Description: A `boolean` value
    - Class Type: `Boolean`
    - Method Name: `#define`

## Registering a Configuration

Once a `ModConfigSpec` has been built, it must be registered to allow NeoForge to load, track, and sync the configuration settings as required. Configurations should be registered in the mod constructor via `ModContainer#registerConfig`. A configuration can be registered with a [given type][configtype] representing the side the config belongs to, the `ModConfigSpec`, and optionally a specific file name for the configuration.

```java
// In the main mod file with a ModConfigSpec CONFIG_SPEC
public ExampleMod(ModContainer container) {
    ...
    //Register the config
    container.registerConfig(ModConfig.Type.COMMON, ExampleConfig.CONFIG_SPEC);
    ...
}
```

### Configuration Types

Configuration types determine where the configuration file is located, what time it is loaded, and whether the file is synced across the network. All configurations are, by default, either loaded from `.minecraft/config` on the physical client or `<server_folder>/config` on the physical server. Some nuances between each configuration type can be found in the following subsections.

:::tip
NeoForge documents the [config types][type] within their codebase.
:::

- `STARTUP`
    - Loaded on both the physical client and physical server from the config folder
    - Read immediately on registration
    - **NOT** synced across the network
    - Suffixed with `-startup` by default

:::warning
Configurations registered under the `STARTUP` type can cause desyncs between the client and server, such as if the configuration is used to disable the registration of content. Therefore, it is highly recommended that any configurations within `STARTUP` are not used to enable or disable features that may change the content of the mod.
:::

- `CLIENT`
    - Loaded **ONLY** on the physical client from the config folder
        - There is no server location for this configuration type
    - Read immedately before `FMLCommonSetupEvent` is fired
    - **NOT** synced across the network
    - Suffixed with `-client` by default
- `COMMON`
    - Loaded on both the physical client and physical server from the config folder
    - Read immedately before `FMLCommonSetupEvent` is fired
    - **NOT** synced across the network
    - Suffixed with `-common` by default
- `SERVER`
    - Loaded on both the physical client and physical server from the config folder
        - Can be overridden for each world by adding a config to:
            - Client: `.minecraft/saves/<world_name>/serverconfig`
            - Server: `<server_folder>/world/serverconfig`
    - Read immedately before `ServerAboutToStartEvent` is fired
    - Synced across the network to the client
    - Suffixed with `-server` by default

## Configuration Events

Operations that occur whenever a config is loaded, reloaded, or unloaded can be done using the `ModConfigEvent.Loading`, `ModConfigEvent.Reloading`, and `ModConfigEvent.Unloading` events. The events must be [registered][events] to the mod event bus.

:::caution
These events are called for all configurations for the mod; the `ModConfig` object provided should be used to denote which configuration is being loaded or reloaded.
:::

## Configuration Screen

A configuration screen allows users to edit the config values for a mod while in-game without needing to open any files. The screen will automatically parse your registered config files and populate the screen. 

A mod can use the built-in configuration screen that NeoForge provides. Mods can extend `ConfigurationScreen` to change the behavior of the default screen or make their own configuration screen. Mods can also create their own screen from scratch and provide that custom screen to NeoForge through the below extension point.

A configuration screen can be registered for a mod by registering a `IConfigScreenFactory` extension point during mod construction on the [client]:

```java
// In the main client mod file
public ExampleModClient(ModContainer container) {
    ...
    // This will use NeoForge's ConfigurationScreen to display this mod's configs
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    ...
}
```

The configuration screen can be accessed in game by going to the 'Mods' page, selecting the mod from the sidebar, and clicking the 'Config' button. Startup, Common, and Client config options will always be editable at any point. Server configs are only editable in the screen when playing on a world locally. If connected to a server or to another person's LAN world, Server config option will be disabled in the screen. The first page of the config screen for the mod will show every registered config file for players to pick which one to edit.

:::warning
Translation keys should be added and have the text defined within the lang JSON for all config entries if you are making a screen.

You can specify a translation key for a config by using the `ModConfigSpec$Builder#translation` method, so we can extend the previous code to:
```java
ConfigValue<T> value = builder.comment("This value is called 'config_value_name', and is set to defaultValue if no existing config is present")
    .translation("modid.config.config_value_name")
    .define("config_value_name", defaultValue);
```

To make translating easier, open the configuration screen and visit all of the configs and their subsections. Then back out to the mod list screen. All untranslated config entries that were encountered will be printed to the console at this point. This makes it easier to know what to translate and what the translation keys are.
:::

[toml]: https://toml.io/
[nightconfig]: https://github.com/TheElectronWill/night-config
[configtype]: #configuration-types
[type]: https://github.com/neoforged/FancyModLoader/blob/aafe4660ae6eff2702ec786dba8e83c69c0d9e91/loader/src/main/java/net/neoforged/fml/config/ModConfig.java#L88-L121
[events]: ../concepts/events.md#registering-an-event-handler
[client]: ../concepts/sides.md#mod

## misc/debugprofiler

# Debug Profiler

Minecraft provides a Debug Profiler that provides system data, current game settings, JVM data, level data, and sided tick information to find time consuming code. Considering things like `TickEvent`s and ticking `BlockEntity`s, this can be very useful for modders and server owners that want to find a lag source.

## Using the Debug Profiler

The Debug Profiler is very simple to use. It requires the debug keybind `F3 + L` to start the profiler. After 10 seconds, it will automatically stop; however, it can be stopped earlier by pressing the keybind again.

:::note
Naturally, you can only profile code paths that are actually being reached. [`Entity`s][entity] and [`BlockEntity`s][blockentity] that you want to profile must exist in the level to show up in the results.
:::

After you have stopped the debugger, it will create a new zip within the `debug/profiling` subdirectory in your run directory.
The file name will be formatted with the date and time as `yyyy-mm-dd_hh_mi_ss-WorldName-VersionNumber.zip`

## Reading a Profiling result

Within each sided folder (`client` and `server`), you will find a `profiling.txt` file containing the result data. At the top, it first tells you how long in milliseconds it was running and how many ticks ran in that time.

Below that, you will find information similar to the snippet below:

```
[00] tick(201/1) - 41.46%/41.46%
[01] |   levels(201/1) - 96.62%/40.05%
[02] |   |   ServerLevel[New World] minecraft:overworld(201/1) - 98.80%/39.58%
[03] |   |   |   tick(201/1) - 99.98%/39.57%
[04] |   |   |   |   entities(201/1) - 56.83%/22.49%
[05] |   |   |   |   |   tick(44717/222) - 95.81%/21.54%
[06] |   |   |   |   |   |   minecraft:skeleton(4585/23) - 13.91%/3.00%
[07] |   |   |   |   |   |   |   #tickNonPassenger 4585/22
[07] |   |   |   |   |   |   |   travel(4573/23) - 33.12%/0.99%
[08] |   |   |   |   |   |   |   |   #getChunkCacheMiss 7/0
[08] |   |   |   |   |   |   |   |   #getChunk 47227/234
[08] |   |   |   |   |   |   |   |   move(4573/23) - 40.10%/0.40%
[09] |   |   |   |   |   |   |   |   |   #getEntities 4573/22
[09] |   |   |   |   |   |   |   |   |   #getChunkCacheMiss 1353/6
[09] |   |   |   |   |   |   |   |   |   #getChunk 28482/141
[08] |   |   |   |   |   |   |   |   unspecified(4573/23) - 36.24%/0.36%
[08] |   |   |   |   |   |   |   |   rest(4573/23) - 23.66%/0.23%
[09] |   |   |   |   |   |   |   |   |   #getChunkCacheMiss 59/0
[09] |   |   |   |   |   |   |   |   |   #getChunk 65867/327
[09] |   |   |   |   |   |   |   |   |   #getChunkNow 531/2
```

Some entries look like `[03] tick(201/1) - 99.98%/39.57%` as a result of `ProfilerFiller#push` and `pop`. This means:

- `[03]` - The depth of the section.
- `tick` - The name of the section.
    - `unspecified` if the duration of time did not have an associated subsection.
- `201` - The number of times this section was called during the profiler's runtime.
- `1` - The average number of times, rounded down, this section was called during a single tick.
- `99.98%` - The percentage of time taken in relation to its parent.
    - For Layer 0, it is the percentage of the time a tick takes.
    - For Layer 1, it is the percentage of the time its parent takes.
- `39.57%` - The percentage of time taken from the entire tick.

There are also some entries that look like `[07] #tickNonPassenger 4585/22` as a result of `ProfileFiller#incrementCounter`. This means:

- `[07]` - The depth of the section.
- `#tickNonPassenger` - The name of the counter being incremented.
    - The `#` is prepended automatically.
- `4585` - The number of times this counter was incremented during the profiler's runtime.
- `22` - The average number of times, rounded down, this counter was incremented during a single tick.

## Profiling your own code

The Debug Profiler has basic support for `Entity` and `BlockEntity`. If you would like to profile something else, you may need to manually create your sections like so:

```java
Profiler.get().push("yourSectionName");
//The code you want to profile
Profiler.get().pop();
```

Now you just need to search the results file for your section name.

[blockentity]: ../blockentities/index.md
[entity]: ../entities/index.md

## misc/gametest

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Game Tests

Game Tests are a way to run in-game unit tests. The system was designed to be scalable and in parallel to run large numbers of different tests efficiently. Testing object interactions and behaviors are simply a few of the many applications of this framework. As the system can either be implemented fully in-code or via [datapacks], both will be shown below.

## Creating a Game Test

A standard Game Test follows four basic steps:

1. A structure, or template, is loaded holding the scene on which the interaction or behavior is tested.
1. An environment for the test to run in.
1. A registered function to run the logic. If a successful state is reached, then the test succeeds. Otherwise, the test fails and the result is stored within a lectern adjacent to the scene.
1. A test instance to link the other three objects together.

## The Test Data

All test instances hold some `TestData` which defines how a game test should be run, from its initial configurations to the environment and structure template to use. As the `TestData` is serialized as a `MapCodec`, the data is stored at the root level of the file along with all the other instance-specific parameters.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some game test examplemod:example_test
// In 'data/examplemod/test_instance/example_test.json'
{
    // `TestData`

    // The environment to run the test in
    // Points to 'data/examplemod/test_environment/example_environment.json'
    "environment": "examplemod:example_environment",

    // The structure used for the game test
    // Points to 'data/examplemod/structure/example_structure.nbt'
    "structure": "examplemod:example_structure",

    // The number of ticks that the game test will run until it automatically fails
    "max_ticks": 400,

    // The number of ticks that are used to setup everything required for the game test
    // This is not counted towards the maximum number of ticks the test can take
    // If not specified, defaults to 0
    "setup_ticks": 50,

    // Whether the test is required to succeed to mark the batch run as successful
    // If not specified, defaults to true
    "required": true,

    // Specifies how the structure and all subsequent helper methods should be rotated for the test
    // If not specified, nothing is rotated
    // Can be 'none', 'clockwise_90', '180', 'counterclockwise_90'
    "rotation": "clockwise_90",

    // When true, the test can only be ran through the `/test` command
    // If not specified, defaults to false
    "manual_only": true,

    // Specifies the maximum number of times that the test can be reran
    // If not specified, defaults to 1
    "max_attempts": 3,

    // Specifies the minimum number of successes that must occur for a test to be marked as successful
    // This must be less than or equal to the maximum number of attempts allowed
    // If not specified, defaults to 1
    "required_successes": 1,

    // Returns whether the structure boundary should keep the top empty
    // This is currently only used in block-based test instances
    // If not specified, defaults to false 
    "sky_access": false

    // ...
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_INSTANCE, bootstrap -> {
            // Use this to get the test environments
            HolderGetter<TestEnvironmentDefinition<?>> environments = bootstrap.lookup(Registries.TEST_ENVIRONMENT);

            // Register a game test
            // Any fields not relevant to the test data are hidden
            bootstrap.register(..., new FunctionGameTestInstance(...,
                new TestData<>(
                    // The environment to run the test in
                    // Points to 'data/examplemod/test_environment/example_environment.json'
                    environments.getOrThrow(EXAMPLE_ENVIRONMENT),

                    // The structure used for the game test
                    // Points to 'data/examplemod/structure/example_structure.nbt'
                    Identifier.fromNamespaceAndPath("examplemod", "example_structure"),

                    // The number of ticks that the game test will run until it automatically fails
                    400,

                    // The number of ticks that are used to setup everything required for the game test
                    // This is not counted towards the maximum number of ticks the test can take
                    // If not specified, defaults to 0
                    50,

                    // Whether the test is required to succeed to mark the batch run as successful
                    // If not specified, defaults to true
                    true,

                    // Specifies how the structure and all subsequent helper methods should be rotated for the test
                    // If not specified, nothing is rotated
                    // Can be 'none', 'clockwise_90', '180', 'counterclockwise_90'
                    Rotation.CLOCKWISE_90,

                    // When true, the test can only be ran through the `/test` command
                    // If not specified, defaults to false
                    true,

                    // Specifies the maximum number of times that the test can be reran
                    // If not specified, defaults to 1
                    3,

                    // Specifies the minimum number of successes that must occur for a test to be marked as successful
                    // This must be less than or equal to the maximum number of attempts allowed
                    // If not specified, defaults to 1
                    1,

                    // Returns whether the structure boundary should keep the top empty
                    // This is currently only used in block-based test instances
                    // If not specified, defaults to false 
                    false
                )
            ));
        })
    );
}
```

</TabItem>
</Tabs>

## Structure Templates

Game Tests are performed within scenes loaded by structures, or templates. All templates define the dimensions of the scene and the initial data (blocks and entities) that will be loaded. The template must be stored as an `.nbt` file within `data/<namespace>/structure`. `TestData#structure` references the NBT file using a relative `Identifier` (e.g., `examplemod:example_structure` points to `data/examplemod/structure/example_structure.nbt`)

## Test Environments

All game tests run in some `TestEnvironmentDefinition`, determining how the current `ServerLevel` should be set up. Then, once the test has finished, the environment is tore down, letting the next instance or instances run. All environments are batched, meaning that if multiple test instances have the same environment, they will run at the same time. All test environments are located within `data/<namespace>/test_environment/<path>.json`.

Vanilla provides `minecraft:default`, which does not modify the `ServerLevel`. However, there are other supported definition types that can be used to construct an environment.

### Game Rules

This environment type sets the game rules to use for the test. During teardown, the game rules are reset to their default value.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// examplemod:example_environment
// In 'data/examplemod/test_environment/example_environment.json'
{
    "type": "minecraft:game_rules",

    // A map of game rules to their set values
    "rules": {
        "minecraft:fire_damage": false,
        "minecraft:players_sleeping_percentage": 50
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_ENVIRONMENT, bootstrap -> {

            // Register the environment
            bootstrap.register(
                EXAMPLE_ENVIRONMENT,
                new TestEnvironmentDefinition.SetGameRules(
                    new GameRuleMap.Builder()
                        // A map of game rules to their set values
                        .set(GameRules.FIRE_DAMAGE, false)
                        .set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 50)
                        .build()
                )
            );
        })
    );
}
```

</TabItem>
</Tabs>

### Clock Time

This environment type sets the specified `WorldClock` time to some non-negative integer, like how the `/time of <clock> set <number>` command is used.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// examplemod:example_environment
// In 'data/examplemod/test_environment/example_environment.json'
{
    "type": "minecraft:clock_time",

    // The clock to set the time of
    // Points to a registered clock at `data/<namespace>/world_clock/<path>.json`
    "clock": "minecraft:overworld",

    // Sets the time of the clock
    "time": 13000
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_ENVIRONMENT, bootstrap -> {
            // Getting clocks
            HolderGetter<WorldClock> clocks = bootstrap.lookup(Registries.WORLD_CLOCK);

            // Register the environment
            bootstrap.register(
                EXAMPLE_ENVIRONMENT,
                new TestEnvironmentDefinition.ClockTime(
                    // The clock to set the time of
                    clocks.getOrThrow(WorldClocks.OVERWORLD),
                    // Sets the time of the clock
                    13000
                )
            );
        })
    );
}
```

</TabItem>
</Tabs>

### Timeline Attributes

This environment type sets the timelines to apply to the environment attributes in a level.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// examplemod:example_environment
// In 'data/examplemod/test_environment/example_environment.json'
{
    "type": "minecraft:timeline_attributes",

    // The timelines to apply to the level
    "timelines": [
        "minecraft:day",
        "minecraft:moon"
    ]
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_ENVIRONMENT, bootstrap -> {
            // Getting timelines
            HolderGetter<Timeline> timelines = bootstrap.lookup(Registries.TIMELINE);

            // Register the environment
            bootstrap.register(
                EXAMPLE_ENVIRONMENT,
                new TestEnvironmentDefinition.Timelines(
                    // The timelines to apply to the level
                    List.of(
                        timelines.getOrThrow(Timelines.OVERWORLD_DAY),
                        timelines.getOrThrow(Timelines.MOON)
                    )
                )
            );
        })
    );
}
```

</TabItem>
</Tabs>

### Weather

This environment type sets the weather, like to how the `/weather` command is used.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// examplemod:example_environment
// In 'data/examplemod/test_environment/example_environment.json'
{
    "type": "minecraft:weather",

    // Can be one of three values:
    // - clear   (No weather)
    // - rain    (Rain)
    // - thunder (Rain and thunder)
    "weather": "thunder"
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_ENVIRONMENT, bootstrap -> {

            // Register the environment
            bootstrap.register(
                EXAMPLE_ENVIRONMENT,
                new TestEnvironmentDefinition.Weather(
                    // Can be one of three values:
                    // - clear   (No weather)
                    // - rain    (Rain)
                    // - thunder (Rain and thunder)
                    TestEnvironmentDefinition.Weather.Type.THUNDER
                )
            );
        })
    );
}
```

</TabItem>
</Tabs>

### Minecraft Functions

This environment type provides two Identifiers to `mcfunction`s to setup and teardown the level, respectively.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// examplemod:example_environment
// In 'data/examplemod/test_environment/example_environment.json'
{
    "type": "minecraft:function",

    // The setup mcfunction to use
    // If not specified, nothing will be ran
    // Points to 'data/examplemod/function/example/setup.mcfunction'
    "setup": "examplemod:example/setup",

    // The teardown mcfunction to use
    // If not specified, nothing will be ran
    // Points to 'data/examplemod/function/example/teardown.mcfunction'
    "teardown": "examplemod:example/teardown"
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_ENVIRONMENT, bootstrap -> {

            // Register the environment
            bootstrap.register(
                EXAMPLE_ENVIRONMENT,
                new TestEnvironmentDefinition.Functions(
                    // The setup mcfunction to use
                    // If not specified, nothing will be ran
                    // Points to 'data/examplemod/function/example/setup.mcfunction'
                    Optional.of(Identifier.fromNamespaceAndPath("examplemod", "example/setup")),

                    // The teardown mcfunction to use
                    // If not specified, nothing will be ran
                    // Points to 'data/examplemod/function/example/teardown.mcfunction'
                    Optional.of(Identifier.fromNamespaceAndPath("examplemod", "example/teardown"))
                )
            );
        })
    );
}
```

</TabItem>
</Tabs>

### Composites

Multiple environments can be merged using the composite environment type. The list of definitions can take in either a reference to an existing definiton, or an inlined definition.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// examplemod:example_environment
// In 'data/examplemod/test_environment/example_environment.json'
{
    "type": "minecraft:all_of",

    // A list of test environments to use
    // Can either specified the registry name or the environment itself
    "definitions": [
        // Points to 'data/minecraft/test_environment/default.json'
        "minecraft:default",
        {
            // A raw environment definition
            "type": "..."
        }
        // ...
    ]
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_ENVIRONMENT, bootstrap -> {
            // Getting existing environments
            HolderGetter<TestEnvironmentDefinition<?>> environments = bootstrap.lookup(Registries.TEST_ENVIRONMENT);

            // Register the environment
            bootstrap.register(
                EXAMPLE_ENVIRONMENT,
                new TestEnvironmentDefinition.AllOf(
                    List.of(
                        // Points to 'data/minecraft/test_environment/default.json'
                        environments.getOrThrow(GameTestEnvironments.DEFAULT_KEY),
                        Holder.direct(
                            // Create a new TestEnvironmentDefinition here
                            ...
                        )
                        // ...
                    )
                )
            );
        })
    );
}
```

</TabItem>
</Tabs>

### Custom Definition Types

A custom `TestEnvironmentDefinition<SavedDataType>` type provides three methods: `setup` to modify the `ServerLevel` and return the previous `SavedDataType` generic state, `teardown` to reset what was modified using the `SavedDataType`, and `codec` to provide the `MapCodec` to encode and decode the type:

```java
public record ExampleEnvironmentType(int value1, boolean value2) implements TestEnvironmentDefinition<Pair<Integer, Boolean>> {

    // Construct the map codec to register
    public static final MapCodec<ExampleEnvironmentType> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("value1").forGetter(ExampleEnvironmentType::value1),
            Codec.BOOL.fieldOf("value2").forGetter(ExampleEnvironmentType::value2)
        ).apply(instance, ExampleEnvironmentType::new)
    );

    @Override
    public Pair<Integer, Boolean> setup(ServerLevel level) {
        // Setup whatever is necessary here
        // return the original values of the modified level data
    }

    @Override
    public void teardown(ServerLevel level, Pair<Integer, Boolean> originalState) {
        // Undo whatever was changed within the setup method
        // This use the original state to reset the data
    }

    @Override
    public MapCodec<ExampleEnvironmentType> codec() {
        return CODEC;
    }
}
```

Then, the `MapCodec` can be [registered]:

```java
public static final DeferredRegister<MapCodec<? extends TestEnvironmentDefinition>> TEST_ENVIRONMENT_DEFINITION_TYPES = DeferredRegister.create(
        BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE,
        "examplemod"
);

public static final Supplier<MapCodec<ExampleEnvironmentType>> EXAMPLE_ENVIRONMENT_CODEC = TEST_ENVIRONMENT_DEFINITION_TYPES.register(
    "example_environment_type",
    () -> ExampleEnvironmentType.CODEC
);
```

Finally, the type can then be used in your environment definition:

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// examplemod:example_environment
// In 'data/examplemod/test_environment/example_environment.json'
{
    "type": "examplemod:example_environment_type",

    "value1": 0,
    "value2": true
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_ENVIRONMENT, bootstrap -> {

            // Register the environment
            bootstrap.register(
                EXAMPLE_ENVIRONMENT,
                new ExampleEnvironmentType(
                    0, true
                )
            );
        })
    );
}
```

</TabItem>
</Tabs>

## The Test Function

The basic concept of game tests are structured around running some method that takes in a `GameTestHelper` and returning nothing. Calling the methods within the `GameTestHelper` determines whether the test succeeds or fails. Each test function is [registered], allowing it to be referenced in a test instance:

```java
public class ExampleFunctions {

    // Here is our example function
    public static void exampleTest(GameTestHelper helper) {
        // Do Stuff
    }
}

// Register our function for use
public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTION = DeferredRegister.create(
        BuiltInRegistries.TEST_FUNCTION,
        "examplemod"
);

public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> EXAMPLE_FUNCTION = TEST_FUNCTION.register(
    "example_function",
    () -> ExampleFunctions::exampleTest
);
```

### Relative Positioning

All test functions translate relative coordinates within the structure template scene to its absolute coordinates using the structure block's current location. To allow for easy conversion between relative and absolute positioning, `GameTestHelper#absolutePos` and `GameTestHelper#relativePos` can be used respectively.

The relative position of a structure template can be obtained in-game by loading the structure via the [test command][test], placing the player at the wanted location, and finally running the `/test pos` command. This will grab the coordinates of the player relative to the closest structure within 200 blocks of the player. The command will export the relative position as a copyable text component in the chat to be used as a final local variable.

:::tip
The local variable generated by `/test pos` can specify its reference name by appending it to the end of the command:

```bash
/test pos <var> # Exports 'final BlockPos <var> = new BlockPos(...);'
```
:::

### Successful Completion

A test function is responsible for one thing: marking the test was successful on a valid completion. If no success state was achieved before the timeout is reached (as defined by `TestData#maxTicks`), then the test automatically fails.

There are many abstracted methods within `GameTestHelper` which can be used to define a successful state; however, four are extremely important to be aware of.

Method               | Description
:---:                | :---
`#succeed`           | The test is marked as successful.
`#succeedIf`         | The supplied `Runnable` is tested immediately and succeeds if no `GameTestAssertException` is thrown. If the test does not succeed on the immediate tick, then it is marked as a failure.
`#succeedWhen`       | The supplied `Runnable` is tested every tick until timeout and succeeds if the check on one of the ticks does not throw a `GameTestAssertException`.
`#succeedOnTickWhen` | The supplied `Runnable` is tested on the specified tick and will succeed if no `GameTestAssertException` is thrown. If the `Runnable` succeeds on any other tick, then it is marked as a failure.

:::caution
Game Tests are executed every tick until the test is marked as a success. As such, methods which schedule success on a given tick must be careful to always fail on any previous tick.
:::

### Scheduling Actions

Not all actions will occur when a test begins. Actions can be scheduled to occur at specific times or intervals:

Method           | Description
:---:            | :---
`#runAtTickTime` | The action is ran on the specified tick.
`#runAfterDelay` | The action is ran `x` ticks after the current tick.
`#onEachTick`    | The action is ran every tick.

### Assertions

At any time during a Game Test, an assertion can be made to check if a given condition is true. There are numerous assertion methods within `GameTestHelper`; however, it simplifies to throwing a `GameTestAssertException` whenever the appropriate state is not met.

## Registering The Test Instance

With the `TestData`, `TestEnvironmentDefinition`, and test function in hand, we can now link everything together through a `GameTestInstance`. Each test instance is what represents a single game test to run. All test instances are located within `data/<namespace>/test_instance/<path>.json`.

### Function-Based Tests

`FunctionGameTestInstance` links a `TestData` to some registered test function. The test instance will run the test function when called.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some game test examplemod:example_test
// In 'data/examplemod/test_instance/example_test.json'
{
    // `TestData`

    "environment": "examplemod:example_environment",
    "structure": "examplemod:example_structure",
    "max_ticks": 400,
    "setup_ticks": 50,
    "required": true,
    "rotation": "clockwise_90",
    "manual_only": true,
    "max_attempts": 3,
    "required_successes": 1,
    "sky_access": false,

    // `FunctionGameTestInstance`
    "type": "minecraft:function",

    // Points to a 'Consumer<GameTestHelper>' in the test function registry
    "function": "examplemod:example_function"
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// The test instance key
public static final ResourceKey<GameTestInstance> EXAMPLE_TEST_INSTANCE = ResourceKey.create(
    Registries.TEST_INSTANCE,
    Identifier.fromNamespaceAndPath("examplemod", "example_test")
);

// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_INSTANCE, bootstrap -> {
            // Use this to get the test environments
            HolderGetter<TestEnvironmentDefinition<?>> environments = bootstrap.lookup(Registries.TEST_ENVIRONMENT);

            // Register a game test
            // Any fields not relevant to the test data are hidden
            bootstrap.register(EXAMPLE_TEST_INSTANCE,
                new FunctionGameTestInstance(
                    // Points to a 'Consumer<GameTestHelper>' in the test function registry
                    EXAMPLE_FUNCTION.getKey()
                    new TestData<>(
                        environments.getOrThrow(EXAMPLE_ENVIRONMENT),
                        Identifier.fromNamespaceAndPath("examplemod", "example_structure"),
                        400,
                        50,
                        true,
                        Rotation.CLOCKWISE_90,
                        true,
                        3,
                        1,
                        false
                    )
            ));
        })
    );
}
```

</TabItem>
</Tabs>

### Block-Based Tests

`BlockBasedTestInstance` is a special kind of test instance that relies on redstone signals sent and received by `Blocks#TEST_BLOCK`s. For this test to work, the structure template must contain at least two test blocks: one and only one set to `TestBlockMode#START` and one set to `TestBlockMode#ACCEPT`. When the test starts, the starting test block is triggered, sending a fifteen signal pulse for one tick. It is expected that this signal eventually triggers other test blocks in either `LOG`, `FAIL`, or `ACCEPT` states. `LOG` test blocks also send a fifteen signal pulse when activated. `ACCEPT` and `FAIL` test blocks either cause the test instance to succeed or fail, respectively. `ACCEPT` always takes precedence over `FAIL` on a given tick.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some game test examplemod:example_test
// In 'data/examplemod/test_instance/example_test.json'
{
    // `TestData`

    "environment": "examplemod:example_environment",
    "structure": "examplemod:example_structure",
    "max_ticks": 400,
    "setup_ticks": 50,
    "required": true,
    "rotation": "clockwise_90",
    "manual_only": true,
    "max_attempts": 3,
    "required_successes": 1,
    "sky_access": false,

    // `BlockBasedTestInstance`
    "type": "minecraft:block_based"
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// The test instance key
public static final ResourceKey<GameTestInstance> EXAMPLE_TEST_INSTANCE = ResourceKey.create(
    Registries.TEST_INSTANCE,
    Identifier.fromNamespaceAndPath("examplemod", "example_test")
);

// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_INSTANCE, bootstrap -> {
            // Use this to get the test environments
            HolderGetter<TestEnvironmentDefinition<?>> environments = bootstrap.lookup(Registries.TEST_ENVIRONMENT);

            // Register a game test
            // Any fields not relevant to the test data are hidden
            bootstrap.register(EXAMPLE_TEST_INSTANCE,
                new BlockBasedTestInstance(
                    new TestData<>(
                        environments.getOrThrow(EXAMPLE_ENVIRONMENT),
                        Identifier.fromNamespaceAndPath("examplemod", "example_structure"),
                        400,
                        50,
                        true,
                        Rotation.CLOCKWISE_90,
                        true,
                        3,
                        1,
                        false
                    )
            ));
        })
    );
}
```

</TabItem>
</Tabs>

### Custom Test Instances

If you need to implement your own test-based logic for whatever reason, `GameTestInstance` can be extended. Two methods must be implemented: `run`, which represents the test function; and `typeDescription`, which provides a description of the test instance. If the test instance should be used in datagen, it must have a `MapCodec` to be [registered].

```java
public class ExampleTestInstance extends GameTestInstance {

    public ExampleTestInstance(int value1, boolean value2, TestData<Holder<TestEnvironmentDefinition>> info) {
        super(info);
    }

    @Override
    public void run(GameTestHelper helper) {
        // Run whatever game test commands you want
        helper.assertBlockPresent(...);

        // Make sure you have some way to succeed
        helper.succeedIf(() -> ...);
    }

    @Override
    public MapCodec<ExampleTestInstance> codec() {
        return EXAMPLE_INSTANCE_CODEC.get();
    }

    @Override
    protected MutableComponent typeDescription() {
        // Provides a description about what this test is supposed to be
        // Should use a translatable component
        return Component.literal("Example Test Instance");
    }
}

// Register our test instance for use
public static final DeferredRegister<MapCodec<? extends GameTestInstance>> TEST_INSTANCE = DeferredRegister.create(
        BuiltInRegistries.TEST_INSTANCE_TYPE,
        "examplemod"
);

public static final Supplier<MapCodec<? extends GameTestInstance>> EXAMPLE_INSTANCE_CODEC = TEST_INSTANCE.register(
    "example_test_instance",
    () -> RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("value1").forGetter(test -> test.value1),
            Codec.BOOL.fieldOf("value2").forGetter(test -> test.value2),
            TestData.CODEC.forGetter(ExampleTestInstance::info)
        ).apply(instance, ExampleTestInstance::new)
    )
);
```

Then, the test instance can be used in a datapack:

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some game test examplemod:example_test
// In 'data/examplemod/test_instance/example_test.json'
{
    // `TestData`

    "environment": "examplemod:example_environment",
    "structure": "examplemod:example_structure",
    "max_ticks": 400,
    "setup_ticks": 50,
    "required": true,
    "rotation": "clockwise_90",
    "manual_only": true,
    "max_attempts": 3,
    "required_successes": 1,
    "sky_access": false,

    // `ExampleTestInstance`
    "type": "examplemod:example_test_instance",

    "value1": 0,
    "value2": true
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// The test instance key
public static final ResourceKey<GameTestInstance> EXAMPLE_TEST_INSTANCE = ResourceKey.create(
    Registries.TEST_INSTANCE,
    Identifier.fromNamespaceAndPath("examplemod", "example_test")
);

// Let's assume we have some test environment
public static final ResourceKey<TestEnvironmentDefinition<?>> EXAMPLE_ENVIRONMENT = ResourceKey.create(
    Registries.TEST_ENVIRONMENT,
    Identifier.fromNamespaceAndPath("examplemod", "example_environment")
);

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(
        new RegistrySetBuilder().add(Registries.TEST_INSTANCE, bootstrap -> {
            // Use this to get the test environments
            HolderGetter<TestEnvironmentDefinition<?>> environments = bootstrap.lookup(Registries.TEST_ENVIRONMENT);

            // Register a game test
            // Any fields not relevant to the test data are hidden
            bootstrap.register(EXAMPLE_TEST_INSTANCE,
                new ExampleTestInstance(
                    0,
                    true,
                    new TestData<>(
                        environments.getOrThrow(EXAMPLE_ENVIRONMENT),
                        Identifier.fromNamespaceAndPath("examplemod", "example_structure"),
                        400,
                        50,
                        true,
                        Rotation.CLOCKWISE_90,
                        true,
                        3,
                        1,
                        false
                    )
            ));
        })
    );
}
```

</TabItem>
</Tabs>

### Skipping the Datapack

If you don't want to use a datapack to construct your game tests, you can instead listen to the `RegisterGameTestsEvent` on the [mod event bus][event] and register your environments and test instances via `registerEnvironment` and `registerTest`, respectively.

```java
@SubscribeEvent // on the mod event bus
public static void registerTests(RegisterGameTestsEvent event) {
    Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
        // The name of the test environment
        EXAMPLE_ENVIRONMENT.identifier(),
        // A varargs of test environment definitions
        new ExampleEnvironmentType(
            0, true
        )
    );

    event.registerTest(
        // The name of the test instance
        EXAMPLE_TEST_INSTANCE.identifier(),
        new ExampleTestInstance(
            0,
            true,
            new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("examplemod", "example_structure"),
                400,
                50,
                true,
                Rotation.CLOCKWISE_90,
                true,
                3,
                1,
                false
            )
        )
    );
}
```

## Running Game Tests

Game Tests can be run using the `/test` command. The `test` command is highly configurable; however, only a few are of importance to running tests:

| Subcommand  | Description                                           |
|:-----------:|:------------------------------------------------------|
| `run`       | Runs the specified test: `run <test_name>`.           |
| `runall`    | Runs all available tests.                             |
| `runclosest`| Runs the nearest test to the player within 15 blocks. |
| `runthese`  | Runs tests within 200 blocks of the player.           |
| `runfailed` | Runs all tests that failed in the previous run.       |

:::note
Subcommands follow the test command: `/test <subcommand>`.
:::

## Buildscript Configurations

Game Tests provide additional configuration settings within a buildscript (the `build.gradle` file) to run and integrate into different settings.

### Game Test Server Run Configuration

The Game Test Server is a special configuration which runs a build server. The build server returns an exit code of the number of required, failed Game Tests. All failed tests, whether required or optional, are logged. This server can be run using `gradlew runGameTestServer`.

### Enabling Game Tests in Other Run Configurations

By default, only the `client` and `gameTestServer` run configurations have Game Tests enabled. If another run configuration should run Game Tests, then the `neoforge.enableGameTest` property must be set to `true`.

```gradle
// Inside a run configuration
property 'neoforge.enableGameTest', 'true'
```

[datapacks]: ../resources/index.md#data
[registered]: ../concepts/registries.md#methods-for-registering
[test]: #running-game-tests
[event]: ../concepts/events.md#registering-an-event-handler

## misc/identifier

# Identifiers

`Identifier`s are one of the most important things in Minecraft. They are used as keys in [registries][registries], as identifiers for data or resource files, as references to models in code, and in a lot of other places. An `Identifier` consists of two parts: a namespace and a path, separated by a `:`.

The namespace denotes what mod, resource pack or datapack the location refers to. For example, a mod with the mod id `examplemod` will use the `examplemod` namespace. Minecraft uses the `minecraft` namespace. Extra namespaces can be defined at will simply by creating a corresponding data folder, this is usually done by datapacks to keep their logic separate from the point where they integrate with vanilla.

The path is a reference to whatever object you want, inside your namespace. For example, `minecraft:cow` is a reference to something named `cow` in the `minecraft` namespace - usually this location would be used to get the cow entity from the entity registry. Another example would be `examplemod:example_item`, which would probably be used to get your mod's `example_item` from the item registry.

`Identifier`s may only contain lowercase letters, digits, underscores, dots and hyphens. Paths may additionally contain forward slashes. Note that due to Java module restrictions, mod ids may not contain hyphens, which by extension means that mod namespaces may not contain hyphens either (they are still permitted in paths).

:::info
An `Identifier` on its own says nothing about what kind of objects we are using it for. Objects named `minecraft:dirt` exist in multiple places, for example. It is up to whatever receives the `Identifier` to associate an object with it.
:::

A new `Identifier` can be created by calling `Identifier.fromNamespaceAndPath("examplemod", "example_item")` or `Identifier.parse("examplemod:example_item")`. If `withDefaultNamespace` is used, the string will be used as the path, and `minecraft` will be used as the namespace. So for example, `Identifier.withDefaultNamespace("example_item")` will result in `minecraft:example_item`.

The namespace and path of an `Identifier` can be retrieved using `Identifier#getNamespace()` and `#getPath()`, respectively, and the combined form can be retrieved through `Identifier#toString`.

`Identifier`s are immutable. All utility methods on `Identifier`, such as `withPrefix` or `withSuffix`, return a new `Identifier`.

## Resolving `Identifier`s

Some places, for example registries, use `Identifier`s directly. Some other places, however, will resolve the `Identifier` as needed. For example:

- `Identifier`s are used as identifiers for GUI backgrounds. For example, the furnace GUI uses the identifier `minecraft:textures/gui/container/furnace.png`. This maps to the file `assets/minecraft/textures/gui/container/furnace.png` on disk. Note that the `.png` suffix is required in this identifier.
- `Identifier`s are used as identifiers for block models. For example, the block model of dirt uses the identifier `minecraft:block/dirt`. This maps to the file `assets/minecraft/models/block/dirt.json` on disk. Note that the `.json` suffix is not required here. Note as well that this identifier automatically maps into the `models` subfolder.
- `Identifier`s are used as identifiers for client items. For example, the client item of the apple uses the identifier `minecraft:apple` (as defined by `DataComponents#ITEM_MODEL`). This maps to the file `assets/minecraft/items/apple.json`. Note that the `.json` suffix is not required here. Note as well that this identifier automatically maps into the `items` subfolder.
- `Identifier`s are used as identifiers for recipes. For example, the iron block crafting recipe uses the identifier `minecraft:iron_block`. This maps to the file `data/minecraft/recipe/iron_block.json` on disk. Note that the `.json` suffix is not required here. Note as well that this identifier automatically maps into the `recipe` subfolder.

Whether the `Identifier` expects a file suffix, or what exactly the identifier resolves to, depends on the use case.

## `ResourceKey`s

`ResourceKey`s combine a registry id with a registry name. An example would be a registry key with the registry id `minecraft:item` and the registry name `minecraft:diamond_sword`. Unlike an `Identifier`, `ResourceKey`s actually refer to a unique element, thus being able to clearly identify an element. They are most commonly used in contexts where many different registries come in contact with one another. A common use case are datapacks, especially worldgen.

A new `ResourceKey` can be created through the static method `ResourceKey#create(ResourceKey<? extends Registry<T>>, Identifier)`. The second parameter here is the registry name, while the first parameter is what is known as a registry key. Registry keys are a special kind of `ResourceKey` whose registry is the root registry (i.e. the registry of all other registries). A registry key can be created via `ResourceKey#createRegistryKey(Identifier)` with the desired registry's id.

`ResourceKey`s are interned at creation. This means that comparing by reference equality (`==`) is possible and encouraged, but their creation is comparatively expensive.

[registries]: ../concepts/registries.md
[sides]: ../concepts/sides.md

## misc/keymappings

# Key Mappings

A key mapping, or key binding, defines a particular action that should be tied to an input: mouse click, key press, etc. Each action defined by a key mapping can be checked whenever the client can take an input. Furthermore, each key mapping can be assigned to any input through the [Controls option menu][controls].

## Registering a `KeyMapping`

A `KeyMapping` can be registered by listening to the `RegisterKeyMappingsEvent` on the [mod event bus][eventbus] only on the physical client and calling `#register`.

```java
// In some physical client only class

// Key mapping is lazily initialized so it doesn't exist until it is registered
public static final Lazy<KeyMapping> EXAMPLE_MAPPING = Lazy.of(() -> /*...*/);

@SubscribeEvent // on the mod event bus only on the physical client
public static void registerBindings(RegisterKeyMappingsEvent event) {
    event.register(EXAMPLE_MAPPING.get());
}
```

## Creating a `KeyMapping`

A `KeyMapping` can be created using it's constructor. The `KeyMapping` takes in a [translation key][tk] defining the name of the mapping, the default input of the mapping, and a `KeyMapping.Category` defining the category the mapping will be put within in the [Controls option menu][controls].

:::tip
A `KeyMapping` can be added to a custom category by creating a new `KeyMapping.Category` with the `Identifier` and registering it via `RegisterKeyMappingsEvent#registerCategory` on the [mod event bus][eventbus] only on the [physical client][sides]. The associated [translation key][tk] for the category is `key.category.<namespace>.<path>`.

```java
public static final KeyMapping.Category EXAMPLE_CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("examplemod", "category"));

@SubscribeEvent // on the mod event bus only on the physical client
public static void registerBindings(RegisterKeyMappingsEvent event) {
    // Register category
    event.registerCategory(EXAMPLE_CATEGORY);

    // Register binding with category used
    event.register(EXAMPLE_MAPPING.get());
}
```

:::

### Default Inputs

Each key mapping has a default input associated with it. This is provided through `InputConstants.Key`. Each input consists of an `InputConstants.Type`, which defines what device is providing the input, and an integer, which defines the associated identifier of the input on the device.

Vanilla provides three types of inputs: `KEYSYM`, which defines a keyboard through the provided `GLFW` key tokens, `SCANCODE`, which defines a keyboard through the platform-specific scancode, and `MOUSE`, which defines a mouse.

:::note
It is highly recommended to use `KEYSYM` over `SCANCODE` for keyboards as `GLFW` key tokens are not tied to any particular system. You can read more on the [GLFW docs][keyinput].
:::

The integer is dependent on the type provided. All input codes are defined in `GLFW`: `KEYSYM` tokens are prefixed with `GLFW_KEY_*` while `MOUSE` codes are prefixed with `GLFW_MOUSE_*`.

```java
new KeyMapping(
    "key.examplemod.example1", // Will be localized using this translation key
    InputConstants.Type.KEYSYM, // Default mapping is on the keyboard
    GLFW.GLFW_KEY_P, // Default key is P
    KeyMapping.Category.MISC // Mapping will be in the misc category
)
```

:::note
If the key mapping should not be mapped to a default, the input should be set to `InputConstants#UNKNOWN`. The vanilla constructor will require you to extract the input code via `InputConstants$Key#getValue` while the NeoForge constructor can be supplied the raw input field.
:::

### `IKeyConflictContext`

Not all mappings are used in every context. Some mappings are only used in a GUI, while others are only used purely in game. To avoid mappings of the same key used in different contexts conflicting with each other, an `IKeyConflictContext` can be assigned.

Each conflict context contains two methods: `#isActive`, which defines if the mapping can be used in the current game state, and `#conflicts`, which defines whether the mapping conflicts with a key in the same or different conflict context.

Currently, NeoForge defines three basic contexts through `KeyConflictContext`: `UNIVERSAL`, which is the default meaning the key can be used in every context, `GUI`, which means the mapping can only be used when a `Screen` is open, and `IN_GAME`, which means the mapping can only be used if a `Screen` is not open. New conflict contexts can be created by implementing `IKeyConflictContext`.

```java
new KeyMapping(
    "key.examplemod.example2",
    KeyConflictContext.GUI, // Mapping can only be used when a screen is open
    InputConstants.Type.MOUSE, // Default mapping is on the mouse
    GLFW.GLFW_MOUSE_BUTTON_LEFT, // Default mouse input is the left mouse button
    EXAMPLE_CATEGORY // Mapping will be in the new example category
)
```

### `KeyModifier`

Modders may not want mappings to have the same behavior if a modifier key is held at the same (e.g. `G` vs `CTRL + G`). To remedy this, NeoForge adds an additional parameter to the constructor to take in a `KeyModifier` which can apply control (`KeyModifier#CONTROL`), shift (`KeyModifier#SHIFT`), or alt (`KeyModifier#ALT`) to any input. `KeyModifier#NONE` is the default and will apply no modifier.

A modifier can be added in the [controls option menu][controls] by holding down the modifier key and the associated input.

```java
new KeyMapping(
    "key.examplemod.example3",
    KeyConflictContext.UNIVERSAL,
    KeyModifier.SHIFT, // Default mapping requires shift to be held down
    InputConstants.Type.KEYSYM, // Default mapping is on the keyboard
    GLFW.GLFW_KEY_G, // Default key is G
    KeyMapping.Category.MISC
)
```

## Checking a `KeyMapping`

A `KeyMapping` can be checked to see whether it has been clicked. Depending on when, the mapping can be used in a conditional to apply the associated logic.

### Within the Game

Within the game, a mapping should be checked by listening to `ClientTickEvent.Post` on the [event bus][eventbus] and checking `KeyMapping#consumeClick` within a while loop. `#consumeClick` will return `true` only the number of times the input was performed and not already previously handled, so it won't infinitely stall the game.

```java
@SubscribeEvent // on the game event bus only on the physical client
public static void onClientTick(ClientTickEvent.Post event) {
    while (EXAMPLE_MAPPING.get().consumeClick()) {
        // Execute logic to perform on click here
    }
}
```

:::caution
Do not use the `InputEvent`s as an alternative to `ClientTickEvent.Post`. There are separate events for keyboard and mouse inputs only, so they wouldn't handle any additional inputs.
:::

### Inside a GUI

Within a GUI, a mapping can be checked within one of the `GuiEventListener` methods using `IKeyMappingExtension#isActiveAndMatches`. The most common methods which can be checked are `#keyPressed` and `#mouseClicked`. 

`#keyPressed` takes in a `KeyEvent` containing the `GLFW` key token, the platform-specific scan code, and a bitfield of the held down modifiers. A key can be checked against a mapping by creating the input using `InputConstants#getKey`. The modifiers are already checked within the mapping methods itself.

```java
// In some Screen subclass
@Override
public boolean keyPressed(KeyEvent event) {
    if (EXAMPLE_MAPPING.get().isActiveAndMatches(InputConstants.getKey(event))) {
        // Execute logic to perform on key press here
        return true;
    }
    return super.keyPressed(event);
} 
```

:::note
If you do not own the screen which you are trying to check a **key** for, you can listen to the `Pre` or `Post` events of `ScreenEvent.KeyPressed` on the [game event bus][eventbus] instead.
:::

`#mouseClicked` takes in a `MouseButtonEvent` containing the mouse's x position, y position, and the `MouseButtonInfo` clicked; along with a `boolean` for if the user made a double click. A mouse button can be checked against a mapping by creating the input using `InputConstants.Type#getOrCreate` with the `MOUSE` input.

```java
// In some Screen subclass
@Override
public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (EXAMPLE_MAPPING.get().isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(event.button()))) {
        // Execute logic to perform on mouse click here
        return true;
    }
    return super.mouseClicked(event, doubleClick);
} 
```

:::note
If you do not own the screen which you are trying to check a **mouse** for, you can listen to the `Pre` or `Post` events of `ScreenEvent.MouseButtonPressed` on the [game event bus][eventbus] instead.
:::

[eventbus]: ../concepts/events.md#registering-an-event-handler
[controls]: https://minecraft.wiki/w/Options#Controls
[tk]: ../resources/client/i18n.md#components
[keyinput]: https://www.glfw.org/docs/3.3/input_guide.html#input_key
[sides]: ../concepts/sides.md#the-physical-side

## misc/updatechecker

# NeoForge Update Checker

NeoForge provides a very lightweight, opt-in, update-checking framework. If any mods have an available update, it will show a flashing icon on the 'Mods' button of the main menu and mod list along with the respective changelogs. It *does not* download updates automatically.

## Getting Started

The first thing you want to do is specify the `updateJSONURL` parameter in your `mods.toml` file. The value of this parameter should be a valid URL pointing to an update JSON file. This file can be hosted on your own web server, GitHub, or wherever you want as long as it can be reliably reached by all users of your mod.

## Update JSON format

The JSON itself has a relatively simple format as follows:

```json5
{
    "homepage": "<homepage/download page for your mod>",
    "<mcversion>": {
        "<modversion>": "<changelog for this version>", 
        // List all versions of your mod for the given Minecraft version, along with their changelogs
        // ...
    },
    "promos": {
        "<mcversion>-latest": "<modversion>",
        // Declare the latest "bleeding-edge" version of your mod for the given Minecraft version
        "<mcversion>-recommended": "<modversion>",
        // Declare the latest "stable" version of your mod for the given Minecraft version
        // ...
    }
}
```

This is fairly self-explanatory, but some notes:
 
- The link under `homepage` is the link the user will be shown when the mod is outdated.
- NeoForge uses an internal algorithm to determine whether one version string of your mod is "newer" than another. Most versioning schemes should be compatible, but see the `ComparableVersion` class if you are concerned about whether your scheme is supported. Adherence to [Maven versioning][mvnver] is highly recommended.
- The changelog string can be separated into lines using `\n`. Some prefer to include a abbreviated changelog, then link to an external site that provides a full listing of changes.
- Manually inputting data can be chore. You can configure your `build.gradle` to automatically update this file when building a release as Groovy has native JSON parsing support. Doing this is left as an exercise to the reader.

- Some examples can be found here for [nocubes], [Corail Tombstone][corail] and [Chisels & Bits 2][chisel].

## Retrieving Update Check Results

You can retrieve the results of the NeoForge Update Checker using `VersionChecker#getResult(IModInfo)`. You can obtain your `IModInfo` via `ModContainer#getModInfo`, where `ModContainer` can be added as a parameter to your mod constructor. You can obtain any other mod's `ModContainer` using `ModList.get().getModContainerById(<modId>)`. The returned object has a method `#status` which indicates the status of the version check.

|          Status | Description |
|----------------:|:------------|
|        `FAILED` | The version checker could not connect to the URL provided. |
|    `UP_TO_DATE` | The current version is equal to the recommended version. |
|         `AHEAD` | The current version is newer than the recommended version if there is not latest version. |
|      `OUTDATED` | There is a new recommended or latest version. |
| `BETA_OUTDATED` | There is a new latest version. |
|          `BETA` | The current version is equal to or newer than the latest version. |
|       `PENDING` | The result requested has not finished yet, so you should try again in a little bit. |

The returned object will also have the target version and any changelog lines as specified in `update.json`.

[mvnver]: ../gettingstarted/versioning.md
[nocubes]: https://cadiboo.github.io/projects/nocubes/update.json
[corail]: https://github.com/Corail31/tombstone_lite/blob/master/update.json
[chisel]: https://curseupdate.com/231095/chiselsandbits?ml=neoforge

## worldgen/biomemodifier

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Biome Modifiers

Biome Modifiers are a data-driven system that allows for changing many aspects of a biome, including the ability to inject or remove PlacedFeatures, add or remove mob spawns, change the climate, and adjust foliage and water color. NeoForge provides several default biome modifiers that cover the majority of use cases for both players and modders.

### Recommended Section To Read:

- Players or pack developers:
    - [Applying Biome Modifiers](#applying-biome-modifiers)
    - [Built-in Neoforge Biome Modifiers](#built-in-biome-modifiers)

- Modders doing simple additions or removal biome modifications:
    - [Applying Biome Modifiers](#applying-biome-modifiers)
    - [Built-in Neoforge Biome Modifiers](#built-in-biome-modifiers)
    - [Datagenning Biome Modifiers](#datagenning-biome-modifiers)
    - [Targeting Biomes That May Not Exist](#targeting-biomes-that-may-not-exist)

- Modders who want to do custom or complex biome modifications:
    - [Applying Biome Modifiers](#applying-biome-modifiers)
    - [Creating Custom Biome Modifiers](#creating-custom-biome-modifiers)
    - [Datagenning Biome Modifiers](#datagenning-biome-modifiers)
    - [Targeting Biomes That May Not Exist](#targeting-biomes-that-may-not-exist)

## Applying Biome Modifiers

To have NeoForge load a biome modifier JSON file into the game, the file will need to be under `data/<modid>/neoforge/biome_modifier/<path>.json` folder in the mod's resources, or in a [Datapack][datapacks]. Then, once NeoForge loads the biome modifier, it will read its instructions and apply the described modifications to all target biomes when the world is loaded up. Pre-existing biome modifiers from mods can be overridden by datapacks having a new JSON file at the exact same location and name.

The JSON file can be created by hand following the examples in the '[Built-in NeoForge Biome Modifiers](#built-in-biome-modifiers)' section or be datagenned as shown in the '[Datagenning Biome Modifiers](#datagenning-biome-modifiers)' section.

## Built-in Biome Modifiers

These biome modifiers are registered by NeoForge for anyone to use.

### None

This biome modifier has no operation and will do no modification. Pack makers and players can use this in a datapack to disable mods' biome modifiers by overriding their biome modifier JSONs with the JSON below.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:none"
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> NO_OP_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "no_op_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Register the biome modifiers.
    bootstrap.register(NO_OP_EXAMPLE, NoneBiomeModifier.INSTANCE);
});
```

</TabItem>
</Tabs>

### Add Features

This biome modifier type adds `PlacedFeature`s (such as trees or ores) to biomes so that they can spawn during world generation. The modifier takes in the biome id or tag of the biomes the features are added to, a `PlacedFeature` id or tag to add to the selected biomes, and the [`GenerationStep.Decoration`](#Available-Values-for-Decoration-Steps) the features will be generated within.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:add_features",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "#namespace:your_biome_tag",
    // Can either be a placed feature id, such as "examplemod:add_features_example",
    // or a list of placed feature ids, such as ["examplemod:add_features_example", minecraft:ice_spike", ...],
    // or a placed feature tag, such as "#examplemod:placed_feature_tag".
    "features": "namespace:your_feature",
    // See the GenerationStep.Decoration enum in code for a list of valid enum names.
    // The decoration step section further down also has the list of values for reference.
    "step": "underground_ores"
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Assume we have some PlacedFeature named EXAMPLE_PLACED_FEATURE.
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> ADD_FEATURES_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "add_features_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);
    HolderGetter<PlacedFeature> placedFeatures = bootstrap.lookup(Registries.PLACED_FEATURE);

    // Register the biome modifiers.
    bootstrap.register(ADD_FEATURES_EXAMPLE,
        new AddFeaturesBiomeModifier(
            // The biome(s) to generate within
            HolderSet.direct(biomes.getOrThrow(Biomes.PLAINS)),
            // The feature(s) to generate within the biomes
            HolderSet.direct(placedFeatures.getOrThrow(EXAMPLE_PLACED_FEATURE)),
            // The generation step
            GenerationStep.Decoration.LOCAL_MODIFICATIONS
        )
    );
})
```

</TabItem>
</Tabs>

:::warning
Care should be taken when adding vanilla `PlacedFeature`s to biomes, as doing so may cause what is known as a feature cycle violation (two biomes having the same two features in their feature lists, but in different orders within the same `GenerationStep`), leading to a crash. For similar reasons, you should not use the same `PlacedFeature` in more than one biome modifier.

Vanilla `PlacedFeature`s can be referenced in biome JSONs or added via biome modifiers, but should not be used in both. If you still need to add them this way, making a copy of the vanilla `PlacedFeature` under your own namespace is the easiest solution to avoid these problems.
:::

### Remove Features

This biome modifier type removes features (such as trees or ores) from biomes so that they will no longer spawn during world generation. The modifier takes in the biome id or tag of the biomes the features are removed from, a `PlacedFeature` id or tag to remove from the selected biomes, and the [`GenerationStep.Decoration`](#Available-Values-for-Decoration-Steps)s that the features will be removed from.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:remove_features",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "#namespace:your_biome_tag",
    // Can either be a placed feature id, such as "examplemod:add_features_example",
    // or a list of placed feature ids, such as ["examplemod:add_features_example", "minecraft:ice_spike", ...],
    // or a placed feature tag, such as "#examplemod:placed_feature_tag".
    "features": "namespace:problematic_feature",
    // Optional field specifying a GenerationStep, or a list of GenerationSteps, to remove features from.
    // If omitted, defaults to all GenerationSteps.
    // See the GenerationStep.Decoration enum in code for a list of valid enum names.
    // The decoration step section further down also has the list of values for reference.
    "steps": ["underground_ores", "underground_decoration"]
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> REMOVE_FEATURES_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "remove_features_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);
    HolderGetter<PlacedFeature> placedFeatures = bootstrap.lookup(Registries.PLACED_FEATURE);

    // Register the biome modifiers.
    bootstrap.register(REMOVE_FEATURES_EXAMPLE,
        new RemoveFeaturesBiomeModifier(
            // The biome(s) to remove from
            biomes.getOrThrow(Tags.Biomes.IS_OVERWORLD),
            // The feature(s) to remove from the biomes
            HolderSet.direct(placedFeatures.getOrThrow(OrePlacements.ORE_DIAMOND)),
            // The generation steps to remove from
            Set.of(
                GenerationStep.Decoration.LOCAL_MODIFICATIONS,
                GenerationStep.Decoration.UNDERGROUND_ORES
            )
        )
    );
});
```

</TabItem>
</Tabs>

### Add Spawns

_See also [Living Entities/Natural Spawning][spawning]._

This biome modifier type adds entity spawns to biomes. The modifier takes in the biome id or tag of the biomes the entity spawns are added to, and the `SpawnerData` of the entities to add. Each `SpawnerData` contains the entity id, the spawn weight, and the minimum/maximum number of entities to spawn at a given time.

:::note
If you are a modder adding a new entity, make sure the entity has a spawn restriction registered to `RegisterSpawnPlacementsEvent`. Spawn restrictions are used to make entities spawn on surfaces or in water safely. If you do not register a spawn restriction, your entity could spawn in mid-air, fall and die.
:::

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:add_spawns",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "#namespace:biome_tag",
    // Can be either a single object or a list of objects.
    "spawners": [
        {
            "type": "namespace:entity_type", // The id of the entity type to spawn
            "weight": 100, // non-negative int, spawn weight
            "minCount": 1, // positive int, minimum group size
            "maxCount": 4 // positive int, maximum group size
        },
        {
            "type": "minecraft:ghast",
            "weight": 1,
            "minCount": 5,
            "maxCount": 10
        }
    ]
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Assume we have some EntityType<?> named EXAMPLE_ENTITY.
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> ADD_SPAWNS_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "add_spawns_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);

    // Register the biome modifiers.
    bootstrap.register(ADD_SPAWNS_EXAMPLE,
        new AddSpawnsBiomeModifier(
            // The biome(s) to spawn the mobs within
            HolderSet.direct(biomes.getOrThrow(Biomes.PLAINS)),
            // The spawners of the entities to add
            List.of(
                new SpawnerData(EXAMPLE_ENTITY, 100, 1, 4),
                new SpawnerData(EntityType.GHAST, 1, 5, 10)
            )
        )
    );
});
```

</TabItem>
</Tabs>

### Remove Spawns

This biome modifier type removes entity spawns from biomes. The modifier takes in the biome id or tag of the biomes the entity spawns are removed from, and the `EntityType` id or tag of the entities to remove.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:remove_spawns",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "#namespace:biome_tag",
    // Can either be an entity type id, such as "minecraft:ghast",
    // or a list of entity type ids, such as ["minecraft:ghast", "minecraft:skeleton", ...],
    // or an entity type tag, such as "#minecraft:skeletons".
    "entity_types": "#namespace:entitytype_tag"
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> REMOVE_SPAWNS_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "remove_spawns_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);
    HolderGetter<EntityType<?>> entities = bootstrap.lookup(Registries.ENTITY_TYPE);

    // Register the biome modifiers.
    bootstrap.register(REMOVE_SPAWNS_EXAMPLE,
        new RemoveSpawnsBiomeModifier(
            // The biome(s) to remove the spawns from
            biomes.getOrThrow(Tags.Biomes.IS_OVERWORLD),
            // The entities to remove spawns for
            entities.getOrThrow(EntityTypeTags.SKELETONS)
        )
    );
});
```

</TabItem>
</Tabs>

### Add Spawn Costs

Allows for adding new spawn costs to biomes. Spawn costs are a newer way of making mobs spawn spread out in a biome to reduce clustering. It works by having the entities give off a `charge` that surrounds them and adds up with other entities' `charge`. When spawning a new entity, the spawning algorithm looks for a spot where the total `charge` field at the location multiplied by the spawning entity's `charge` value is less than the spawning entity's `energy_budget`. This is an advanced way of spawning mobs, so it is a good idea to reference the Soul Sand Valley biome (which is the most prominent user of this system) for existing values to borrow.

The modifier takes in the biome id or tag of the biomes the spawn costs are added to, the `EntityType` id or tag of the entity types to add spawn costs for, and the `MobSpawnSettings.MobSpawnCost` of the entity. The `MobSpawnCost` contains the energy budget, which indicates the maximum number of entities that can spawn in a location based on the charge provided for each entity spawned.

:::note
If you are a modder adding a new entity, make sure the entity has a spawn restriction registered to `RegisterSpawnPlacementsEvent`.
:::

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:add_spawn_costs",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "#namespace:biome_tag",
    // Can either be an entity type id, such as "minecraft:ghast",
    // or a list of entity type ids, such as ["minecraft:ghast", "minecraft:skeleton", ...],
    // or an entity type tag, such as "#minecraft:skeletons".
    "entity_types": "#minecraft:skeletons",
    "spawn_cost": {
        // The energy budget
        "energy_budget": 1.0,
        // The amount of charge each entity takes up from the budget
        "charge": 0.1
    }
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> ADD_SPAWN_COSTS_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "add_spawn_costs_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);
    HolderGetter<EntityType<?>> entities = bootstrap.lookup(Registries.ENTITY_TYPE);

    // Register the biome modifiers.
    bootstrap.register(ADD_SPAWN_COSTS_EXAMPLE,
        new AddSpawnCostsBiomeModifier(
            // The biome(s) to add the spawn costs to
            biomes.getOrThrow(Tags.Biomes.IS_OVERWORLD),
            // The entities to add the spawn costs for
            entities.getOrThrow(EntityTypeTags.SKELETONS),
            new MobSpawnSettings.MobSpawnCost(
                1.0, // The energy budget
                0.1  // The amount of charge each entity takes up from the budget
            )
        )
    );
});
```

</TabItem>
</Tabs>

### Remove Spawn Costs

Allows for removing a spawn cost from a biome. Spawn costs are a newer way of making mobs spawn spread out in a biome to reduce clustering. The modifier takes in the biome id or tag of the biomes the spawn costs are removed from, and the `EntityType` id or tag of the entities to remove the spawn cost for.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:remove_spawn_costs",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "#namespace:biome_tag",
    // Can either be an entity type id, such as "minecraft:ghast",
    // or a list of entity type ids, such as ["minecraft:ghast", "minecraft:skeleton", ...],
    // or an entity type tag, such as "#minecraft:skeletons".
    "entity_types": "#minecraft:skeletons"
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> REMOVE_SPAWN_COSTS_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "remove_spawn_costs_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);
    HolderGetter<EntityType<?>> entities = bootstrap.lookup(Registries.ENTITY_TYPE);

    // Register the biome modifiers.
    bootstrap.register(REMOVE_SPAWN_COSTS_EXAMPLE,
        new RemoveSpawnCostsBiomeModifier(
            // The biome(s) to remove the spawn costs from
            biomes.getOrThrow(Tags.Biomes.IS_OVERWORLD),
            // The entities to remove spawn costs for
            entities.getOrThrow(EntityTypeTags.SKELETONS)
        )
    );
});
```

</TabItem>
</Tabs>

### Add Legacy Carvers

This biome modifier type allows adding carver caves and ravines to biomes. These are what was used for cave generation before the Caves and Cliffs update. It CANNOT add noise caves to biomes, because noise caves are a part of certain noise-based chunk generator systems and not actually tied to biomes.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
    {
    "type": "neoforge:add_carvers",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "minecraft:plains",
    // Can either be a carver id, such as "examplemod:add_carvers_example",
    // or a list of carver ids, such as ["examplemod:add_carvers_example", "minecraft:canyon", ...],
    // or a carver tag, such as "#examplemod:configured_carver_tag".
    "carvers": "examplemod:add_carvers_example"
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Assume we have some ConfiguredWorldCarver named EXAMPLE_CARVER.
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> ADD_CARVERS_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "add_carvers_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);
    HolderGetter<ConfiguredWorldCarver<?>> carvers = bootstrap.lookup(Registries.CONFIGURED_CARVER);

    // Register the biome modifiers.
    bootstrap.register(ADD_CARVERS_EXAMPLE,
        new AddCarversBiomeModifier(
            // The biome(s) to generate within
            HolderSet.direct(biomes.getOrThrow(Biomes.PLAINS)),
            // The carver(s) to generate within the biomes
            HolderSet.direct(carvers.getOrThrow(EXAMPLE_CARVER))
        )
    );
});
```

</TabItem>
</Tabs>

### Removing Legacy Carvers

This biome modifier type allows removing carver caves and ravines from biomes. These are what was used for cave generation before the Caves and Cliffs update. It CANNOT remove noise caves from biomes, because noise caves are baked into the dimension's noise settings system and not actually tied to biomes.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    "type": "neoforge:remove_carvers",
    // Can either be a biome id, such as "minecraft:plains",
    // or a list of biome ids, such as ["minecraft:plains", "minecraft:badlands", ...],
    // or a biome tag, such as "#c:is_overworld".
    "biomes": "minecraft:plains",
    // Can either be a carver id, such as "examplemod:add_carvers_example",
    // or a list of carver ids, such as ["examplemod:add_carvers_example", "minecraft:canyon", ...],
    // or a carver tag, such as "#examplemod:configured_carver_tag".
    "carvers": "examplemod:add_carvers_example"
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> REMOVE_CARVERS_EXAMPLE = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "remove_carvers_example") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);
    HolderGetter<ConfiguredWorldCarver<?>> carvers = bootstrap.lookup(Registries.CONFIGURED_CARVER);

    // Register the biome modifiers.
    bootstrap.register(REMOVE_CARVERS_EXAMPLE,
        new AddFeaturesBiomeModifier(
            // The biome(s) to remove from
            biomes.getOrThrow(Tags.Biomes.IS_OVERWORLD),
            // The carver(s) to remove from the biomes
            HolderSet.direct(carvers.getOrThrow(Carvers.CAVE))
        )
    );
});
```

</TabItem>
</Tabs>

### Available Values for Decoration Steps

The `step` or `steps` fields in many of the aforementioned JSONs are referring to the `GenerationStep.Decoration` enum. This enum has the steps listed out in the following order, which is the same order that the game uses for generating during worldgen. Try to put features in the step that makes the most sense for them.

|           Step           | Description                                                                             |
|:------------------------:|:----------------------------------------------------------------------------------------|
|     `raw_generation`     | First to run. This is used for special terrain-like features such as Small End Islands. |
|         `lakes`          | Dedicated to spawning pond-like feature such as Lava Lakes.                             |
|  `local_modifications`   | For modifications to terrain such as Geodes, Icebergs, Boulders, or Dripstone.          |
| `underground_structures` | Used for small underground structure-like features such as Dungeons or Fossils.         |
|   `surface_structures`   | For small surface only structure-like features such as Desert Wells.                    |
|      `strongholds`       | Dedicated for Stronghold structures. No feature is added here in unmodified Minecraft.  |
|    `underground_ores`    | The step for all Ores and Veins to be added to. This includes Gold, Dirt, Granite, etc. |
| `underground_decoration` | Used typically for decorating caves. Dripstone Cluster and Sculk Vein are here.         |
|     `fluid_springs`      | The small Lavafalls and Waterfalls come from features in this stage.                    |
|   `vegetal_decoration`   | Nearly all plants (flowers, trees, vines, and more) are added to this stage.            |
| `top_layer_modification` | Last to run. Used for placing Snow and Ice on the surface of cold biomes.               |

## Creating Custom Biome Modifiers

### The `BiomeModifier` Implementation

Under the hood, Biome Modifiers are made up of three parts:

- The [datapack registered][datareg] `BiomeModifier` used to modify the biome builder.
- The [statically registered][staticreg] `MapCodec` that encodes and decodes the modifiers.
- The JSON that constructs the `BiomeModifier`, using the registered id of the `MapCodec` as the indexable type.

A `BiomeModifier` contains two methods: `#modify` and `#codec`. `modify` takes in a `Holder` of the current `Biome`, the current `BiomeModifier.Phase`, and the builder of the biome to modify. Every `BiomeModifier` is called once per `Phase` to organize when certain modifications to the biome should occur:

| Phase               | Description                                                              |
|:-------------------:|:-------------------------------------------------------------------------|
| `BEFORE_EVERYTHING` | A catch-all for everything that needs to run before the standard phases. |
| `ADD`               | Adding features, mob spawns, etc.                                        |
| `REMOVE`            | Removing features, mob spawns, etc.                                      |
| `MODIFY`            | Modifying single values (e.g., climate, colors).                         |
| `AFTER_EVERYTHING`  | A catch-all for everything that needs to run after the standard phases.  |

All `BiomeModifier`s contain a `type` key that references the id of the `MapCodec` used for the `BiomeModifier`. The `codec` takes in the `MapCodec` that encodes and decodes the modifiers. This `MapCodec` is [statically registered][staticreg], with its id used as the `type` of the `BiomeModifier`.

```java
public record ExampleBiomeModifier(HolderSet<Biome> biomes, int value) implements BiomeModifier {
    
    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase == /* Pick the phase that best matches what your want to modify */) {
            // Modify the 'builder', checking any information about the biome itself
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return EXAMPLE_BIOME_MODIFIER.get();
    }
}

// In some registration class
private static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS =
    DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MOD_ID);

public static final Supplier<MapCodec<ExampleBiomeModifier>> EXAMPLE_BIOME_MODIFIER =
    BIOME_MODIFIERS.register("example_biome_modifier", () -> RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(ExampleBiomeModifier::biomes),
            Codec.INT.fieldOf("value").forGetter(ExampleBiomeModifier::value)
        ).apply(instance, ExampleBiomeModifier::new)
    ));
```

## Datagenning Biome Modifiers

A `BiomeModifier` JSON can be created through [data generation][datagen] by passing a `RegistrySetBuilder` to `DatapackBuiltinEntriesProvider`. The JSON will be placed at `data/<modid>/neoforge/biome_modifier/<path>.json`.

For more information on how `RegistrySetBuilder` and `DatapackBuiltinEntriesProvider` work, please see the article on [Data Generation for Datapack Registries][datapackdatagen].

```java
// Define the ResourceKey for our BiomeModifier.
public static final ResourceKey<BiomeModifier> EXAMPLE_MODIFIER = ResourceKey.create(
    NeoForgeRegistries.Keys.BIOME_MODIFIERS, // The registry this key is for
    Identifier.fromNamespaceAndPath(MOD_ID, "example_modifier") // The registry name
);

// BUILDER is a RegistrySetBuilder passed to DatapackBuiltinEntriesProvider
// in a listener for the `GatherDataEvent`s.
BUILDER.add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, bootstrap -> {
    // Lookup any necessary registries.
    // Static registries only need to be looked up if you need to grab the tag data.
    HolderGetter<Biome> biomes = bootstrap.lookup(Registries.BIOME);

    // Register the biome modifiers.
    bootstrap.register(EXAMPLE_MODIFIER,
        new ExampleBiomeModifier(
            biomes.getOrThrow(Tags.Biomes.IS_OVERWORLD),
            20
        )
    );
});
```

This will then result in the following JSON being created:

```json5
// In data/examplemod/neoforge/biome_modifier/example_modifier.json
{
    // The registry key of the MapCodec for the modifier
    "type": "examplemod:example_biome_modifier",
    // All additional settings are applied to the root object
    "biomes": "#c:is_overworld",
    "value": 20
}
```

## Targeting Biomes That May Not Exist

There may be times when a biome modifier needs to target a biome that is not always present in the game. If a biome modifier targets the unregistered biome directly, it will crash on world loading. The way to work around this is to create a biome tag and add the target biome as an optional tag entry by setting required to false for the entry. An example is below:

```json5
{
    "replace": false,
    "values": [
        {
            "id": "minecraft:pale_garden",
            "required": false
        }
    ]
}
```

Using that biome tag for a biome modifier will now not crash if the biome is not registered. One such use case is the Pale Garden biome, which is only created in 1.21.3 when the Winter Drop datapack is turned on. Otherwise, the biome does not exist in the biome registry at all. Another use case can be to target modded biomes while still functioning when the mods adding these biomes are not present.

To datagen optional entries for biome tags, the datagen code would look something along these lines:

```java
// In a KeyTagProvider<Biome> subclass
// Assume we have some example TagKey<Biome> OPTIONAL_BIOMES_TAG
@Override
protected void addTags(HolderLookup.Provider registries) {
    this.tag(OPTIONAL_BIOMES_TAG)
        // Must be a ResourceKey<Biome>
        .addOptional(Biomes.PALE_GARDEN);
}
```

[datagen]: ../resources/index.md#data-generation
[datapackdatagen]: ../concepts/registries#data-generation-for-datapack-registries
[datapacks]: ../resources/index.md#data
[datareg]: ../concepts/registries.md#datapack-registries
[spawning]: ../entities/livingentity.md#natural-spawning
[staticreg]: ../concepts/registries.md#methods-for-registering