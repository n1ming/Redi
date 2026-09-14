# NeoForge 网络与进阶

> 来源:neoforged/Documentation 官方文档(英文原文,API 名与代码签名原样保留)。
> 回答时用中文解释,类名/方法名保持英文。

## advanced/accesstransformers

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Access Transformers

Access Transformers (ATs for short) allow for widening the visibility and modifying the `final` flags of classes, methods, and fields. They allow modders to access and modify otherwise inaccessible members in classes outside their control.

The [specification document][specs] can be viewed on the NeoForged GitHub.

## Adding ATs

Adding an Access Transformer to your mod project is as simple as adding a single line into your `build.gradle`:

Access Transformers need to be declared in `build.gradle`. AT files can be specified anywhere as long as they are copied to the `resources` output directory on compilation.

<Tabs defaultValue="mdg">
<TabItem value="mdg" label="ModDevGradle">

No need to do anything here by default!

</TabItem>
<TabItem value="ng" label="NeoGradle">

```gradle
// In build.gradle:
minecraft {
    accessTransformers {
        file 'src/main/resources/META-INF/accesstransformer.cfg'
    }
}
```

</TabItem>
</Tabs>

By default, NeoForge will search for `META-INF/accesstransformer.cfg`. If the `build.gradle` specifies access transformers in any other location, then their location needs to be defined within `neoforge.mods.toml`:

```toml
# In neoforge.mods.toml:
[[accessTransformers]]
## The file is relative to the output directory of the resources, or the root path inside the jar when compiled
## The 'resources' directory represents the root output directory of the resources
file="META-INF/accesstransformer.cfg"
```

Additionally, multiple AT files can be specified and will be applied in order. This can be useful for larger mods with multiple packages.

<Tabs defaultValue="mdg">
<TabItem value="mdg" label="ModDevGradle">

```gradle
// In build.gradle:
neoForge {
    // ModDevGradle already tries to include 'src/main/resources/META-INF/accesstransformer.cfg' by default
    accessTransformers.from 'src/additions/resources/accesstransformer_additions.cfg'
}
```

</TabItem>
<TabItem value="ng" label="NeoGradle">

```gradle
// In build.gradle:
minecraft {
    accessTransformers {
        file 'src/main/resources/META-INF/accesstransformer.cfg'
        file 'src/additions/resources/accesstransformer_additions.cfg'
    }
}
```

</TabItem>
</Tabs>

```toml
# In neoforge.mods.toml
[[accessTransformers]]
file="accesstransformer.cfg"

[[accessTransformers]]
file="accesstransformer_additions.cfg"
```

After adding or modifying any Access Transformer, the Gradle project must be refreshed for the transformations to take effect.

## The Access Transformer Specification

### Comments

All text after a `#` until the end of the line will be treated as a comment and will not be parsed.

### Access Modifiers

Access modifiers specify to what new member visibility the given target will be transformed to. In decreasing order of visibility:

- `public` - visible to all classes inside and outside its package
- `protected` - visible only to classes inside the package and subclasses
- `default` - visible only to classes inside the package
- `private` - visible only to inside the class

A special modifier `+f` and `-f` can be appended to the aforementioned modifiers to either add or remove respectively the `final` modifier, which prevents subclassing, method overriding, or field modification when applied.

:::danger
Directives only modify the method they directly reference; any overriding methods will not be access-transformed. It is advised to ensure transformed methods do not have non-transformed overrides that restrict the visibility, which will result in the JVM throwing an error.

Examples of methods that can be safely transformed are `final` methods (or methods in `final` classes), and `static` methods. `private` methods are generally safe as well; however, they could cause unintentional overrides in any subtypes, so some additional manual validation should be performed.
:::

### Targets and Directives

#### Classes

To target classes:

```
<access modifier> <fully qualified class name>
```

Inner classes are denoted by combining the fully qualified name of the outer class and the name of the inner class with a `$` as separator.

#### Fields

To target fields:

```
<access modifier> <fully qualified class name> <field name>
```

#### Methods

Targeting methods require a special syntax to denote the method parameters and return type:

```
<access modifier> <fully qualified class name> <method name>(<parameter types>)<return type>
```

##### Specifying Types

Also called "descriptors": see the [Java Virtual Machine Specification, SE 21, sections 4.3.2 and 4.3.3][jvmdescriptors] for more technical details.

- `B` - `byte`, a signed byte
- `C` - `char`, a Unicode character code point in UTF-16
- `D` - `double`, a double-precision floating-point value
- `F` - `float`, a single-precision floating-point value
- `I` - `integer`, a 32-bit integer
- `J` - `long`, a 64-bit integer
- `S` - `short`, a signed short
- `Z` - `boolean`, a `true` or `false` value
- `[` - references one dimension of an array
    - Example: `[[S` refers to `short[][]`
- `L<class name>;` - references a reference type
    - Example: `Ljava/lang/String;` refers to `java.lang.String` reference type _(note the use of slashes instead of periods)_
- `(` - references a method descriptor, parameters should be supplied here or nothing if no parameters are present
    - Example: `<method>(I)Z` refers to a method that requires an integer argument and returns a boolean
- `V` - indicates a method returns no value, can only be used at the end of a method descriptor
    - Example: `<method>()V` refers to a method that has no arguments and returns nothing

### Examples

```
# Makes public the ByteArrayToKeyFunction interface in Crypt
public net.minecraft.util.Crypt$ByteArrayToKeyFunction

# Makes protected and removes the final modifier from 'random' in MinecraftServer
protected-f net.minecraft.server.MinecraftServer random

# Makes public the 'makeExecutor' method in Util,
# accepting a String and returns a TracingExecutor
public net.minecraft.Util makeExecutor(Ljava/lang/String;)Lnet/minecraft/TracingExecutor;

# Makes public the 'leastMostToIntArray' method in UUIDUtil,
# accepting two longs and returning an int[]
public net.minecraft.core.UUIDUtil leastMostToIntArray(JJ)[I
```

[specs]: https://github.com/NeoForged/AccessTransformers/blob/main/FMLAT.md
[jvmdescriptors]: https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.3.2

## advanced/extensibleenums

# Extensible Enums

Extensible Enums are an enhancement of specific Vanilla enums to allow new entries to be added. This is done by modifying the compiled bytecode of the enum at runtime to add the elements.

## `IExtensibleEnum`

All enums that can have new entries implement the `IExtensibleEnum` interface. This interface acts as a marker to allow the `RuntimeEnumExtender` launch plugin service to know what enums should be transformed.

:::warning
You should **not** be implementing this interface on your own enums. Use maps or registries instead depending on your usecase.  
Enums which are not patched to implement the interface cannot have the interface added to them via mixins or coremods due to the order the transformers run in.
:::

### Creating an Enum Entry

To create new enum entries, a JSON file needs to be created and referenced in the `neoforge.mods.toml` with the `enumExtensions` entry of a `[[mods]]` block. The specified path must be relative to the `resources` directory:

```toml
# In neoforge.mods.toml:
[[mods]]
## The file is relative to the output directory of the resources, or the root path inside the jar when compiled
## The 'resources' directory represents the root output directory of the resources
enumExtensions="META-INF/enumextensions.json"
```

The definition of the entry consists of the target enum's class name, the new field's name (must be prefixed with the mod ID), the descriptor of the constructor to use for constructing the entry and the parameters to be passed to said constructor.

```json5
{
    "entries": [
        {
            // The enum class the entry should be added to
            "enum": "net/minecraft/world/item/ItemDisplayContext",
            // The field name of the new entry, must be prefixed with the mod ID
            "name": "EXAMPLEMOD_STANDING",
            // The constructor to be used
            "constructor": "(ILjava/lang/String;Ljava/lang/String;)V",
            // Constant parameters provided directly.
            "parameters": [ -1, "examplemod:standing", null ]
        },
        {
            "enum": "net/minecraft/world/item/Rarity",
            "name": "EXAMPLEMOD_CUSTOM",
            "constructor": "(ILjava/lang/String;Ljava/util/function/UnaryOperator;)V",
            // The parameters to be used, provided as a reference to an EnumProxy<Rarity> field in the given class
            "parameters": {
                "class": "example/examplemod/MyEnumParams",
                "field": "CUSTOM_RARITY_ENUM_PROXY"
            }
        },
        {
            "enum": "net/minecraft/world/damagesource/DamageEffects",
            "name": "EXAMPLEMOD_TEST",
            "constructor": "(Ljava/lang/String;Ljava/util/function/Supplier;)V",
            // The parameters to be used, provided as a reference to a method in the given class
            "parameters": {
                "class": "example/examplemod/MyEnumParams",
                "method": "getTestDamageEffectsParameter"
            }
        }
    ]
}
```

```java
public class MyEnumParams {
    public static final EnumProxy<Rarity> CUSTOM_RARITY_ENUM_PROXY = new EnumProxy<>(
            Rarity.class, -1, "examplemod:custom", (UnaryOperator<Style>) style -> style.withItalic(true)
    );
    
    public static Object getTestDamageEffectsParameter(int idx, Class<?> type) {
        return type.cast(switch (idx) {
            case 0 -> "examplemod:test";
            case 1 -> (Supplier<SoundEvent>) () -> SoundEvents.DONKEY_ANGRY;
            default -> throw new IllegalArgumentException("Unexpected parameter index: " + idx);
        });
    }
}
```

#### Constructor

The constructor must be specified as a [method descriptor][jvmdescriptors] and must only contain the parameters visible in the source code, omitting the hidden constant name and ordinal parameters.  
If a constructor is marked with the `@ReservedConstructor` annotation, then it cannot be used for modded enum constants.

#### Parameters

The parameters can be specified in three ways with limitations depending on the parameter types:

- Inline in the JSON file as an array of constants (only allowed for primitive values, Strings and for passing null to any reference type)
- As a reference to a field of type `EnumProxy<TheEnum>` in a class from the mod (see `EnumProxy` example above)
    - The first parameter specifies the target enum and the subsequent parameters are the ones to be passed to the enum constructor
- As a reference to a method returning `Object`, where the return value is the parameter value to use. The method must have exactly two parameters of type `int` (index of the parameter) and `Class<?>` (expected type of the parameter)
    - The `Class<?>` object should be used to cast (`Class#cast()`) the return value in order to keep `ClassCastException`s in mod code.

:::warning
The fields and/or methods used as sources for parameter values should be in a separate class to avoid unintentionally loading mod classes too early.
:::

Certain parameters have additional rules:

- If the parameter is an int ID parameter related to a `@IndexedEnum` annotation on the enum, then it is ignored and replaced by the entry's ordinal. If said parameter is specified inline in the JSON, then it must be specified as `-1`, otherwise an exception is thrown.
- If the parameter is a String name parameter related to a `@NamedEnum` annotation on the enum, then it must be prefixed by the mod ID in the `namespace:path` format known from `Identifier`s, otherwise an exception is thrown.

#### Retrieving the Generated Constant

The generated enum constant can be retrieved via `TheEnum.valueOf(String)`. If a field reference is used to provide the parameters, then the constant can also be retrieved from the `EnumProxy` object via `EnumProxy#getValue()`.

## Contributing to NeoForge

To add a new extensible enum to NeoForge, there are at least two required things to do:

- Make the enum implement `IExtensibleEnum` to mark that this enum should be transformed via the `RuntimeEnumExtender`.
- Add a `getExtensionInfo` method that returns `ExtensionInfo.nonExtended(TheEnum.class)`.

Further action is required depending on specific details about the enum:

- If the enum has an int ID parameter which should match the entry's ordinal, then the enum should be annotated with `@IndexedEnum` with the ID's parameter index as the annotation's value if it's not the first parameter
- If the enum has a String name parameter which is used for serialization and should therefore be namespaced, then the enum should be annotated with `@NamedEnum` with the name's parameter index as the annotation's value if it's not the first parameter
- If the enum is sent over the network, then it should be annotated with `@NetworkedEnum` with the annotation's parameter specifying in which direction the values may be sent (clientbound, serverbound or bidirectional)
- If the enum has constructors which are not usable by mods (i.e. because they require registry objects on an enum that may be initialized before modded registration runs), then they should be annotated with `@ReservedConstructor`

:::note
The `getExtensionInfo` method will be transformed at runtime to provide a dynamically generated `ExtensionInfo` if the enum actually had any entries added to it.
:::

```java
// This is an example, not an actual enum within Vanilla

// The first argument must match the enum constant's ordinal
@net.neoforged.fml.common.asm.enumextension.IndexedEnum
// The second argument is a string that must be prefixed with the mod id
@net.neoforged.fml.common.asm.enumextension.NamedEnum(1)
// This enum is used in networking and must be checked for mismatches between the client and server
@net.neoforged.fml.common.asm.enumextension.NetworkedEnum(net.neoforged.fml.common.asm.enumextension.NetworkedEnum.NetworkCheck.BIDIRECTIONAL)
public enum ExampleEnum implements net.neoforged.fml.common.asm.enumextension.IExtensibleEnum {
    // VALUE_1 represents the name parameter here
    VALUE_1(0, "value_1", false),
    VALUE_2(1, "value_2", true),
    VALUE_3(2, "value_3");

    ExampleEnum(int arg1, String arg2, boolean arg3) {
        // ...
    }

    ExampleEnum(int arg1, String arg2) {
        this(arg1, arg2, false);
    }

    public static net.neoforged.fml.common.asm.enumextension.ExtensionInfo getExtensionInfo() {
        return net.neoforged.fml.common.asm.enumextension.ExtensionInfo.nonExtended(ExampleEnum.class);
    }
}
```

[jvmdescriptors]: https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.3.2

## advanced/featureflags

# Feature Flags

Feature flags are a system that allows developers to gate a set of features behind some set of required flags, that being registered elements, gameplay mechanics, data pack entries or some other unique system to your mod.

A common use case would be gating experimental features/elements behind a experimental flag, allowing users to easily switch them on and play around with them before they are finalized.

:::tip
You are not forced to add your own flags. If you find a vanilla flag which would fit your use case, feel free to flag your blocks/items/entities/etc. with said flag.

For example in `1.21.3` if you were to add to the set of Pale Oak wood blocks, you'd only want those to show up if the `WINTER_DROP` flag is enabled.
:::

## Creating a Feature Flag

To create new Feature flags, a JSON file needs to be created and referenced in your `neoforge.mods.toml` file with the `featureFlags` entry inside of your `[[mods]]` block. The specified path must be relative to the `resources` directory:

```toml
# In neoforge.mods.toml:
[[mods]]
    # The file is relative to the output directory of the resources, or the root path inside the jar when compiled
    # The 'resources' directory represents the root output directory of the resources
    featureFlags="META-INF/feature_flags.json"
```

The definition of the entry consists of a list of Feature flag names, which will be loaded and registered during game initialization.

```json5
{
    "flags": [
        // Identifier of a Feature flag to be registered
        "examplemod:experimental"
    ]
}
```

## Retrieving the Feature Flag

The registered Feature flag can be retrieved via `FeatureFlagRegistry.getFlag(Identifier)`. This can be done at any time during your mod's initialization and is recommended to be stored somewhere for future use, rather than looking up the registry each time you require your flag.

```java
// Look up the 'examplemod:experimental' Feature flag
public static final FeatureFlag EXPERIMENTAL = FeatureFlags.REGISTRY.getFlag(Identifier.fromNamespaceAndPath("examplemod", "experimental"));
```

## Feature Elements

`FeatureElement`s are registry values which can be given a set of required flags. These values are only made available to players when the respective required flags match the flags enabled in the level.

When a feature element is disabled, it is fully hidden from the player's view, and all interactions will be skipped. Do note that these disabled elements will still exist in the registry and are merely functionally unusable.

The following is a complete list of all registries which directly implement the `FeatureElement` system:

- Item
- Block
- EntityType
- MenuType
- Potion
- MobEffect
- GameRule

### Flagging Elements

In order to flag a given `FeatureElement` as requiring your Feature flag, you simply pass it and any other desired flags into the respective registration method:

- `Item`: `Item.Properties#requiredFeatures`
- `Block`: `BlockBehaviour.Properties#requiredFeatures`
- `EntityType`: `EntityType.Builder#requiredFeatures`
- `MenuType`: `MenuType#new`
- `Potion`: `Potion#requiredFeatures`
- `MobEffect`: `MobEffect#requiredFeatures`
- `GameRule`: `GameRule#new`

```java
// These elements will only become available once the 'EXPERIMENTAL' flag is enabled

// Item
DeferredRegister.Items ITEMS = DeferredRegister.createItems("examplemod");
DeferredItem<Item> EXPERIMENTAL_ITEM = ITEMS.registerSimpleItem("experimental", props -> props
    .requiredFeatures(EXPERIMENTAL) // mark as requiring the 'EXPERIMENTAL' flag
);

// Block
DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("examplemod");
// Do note that BlockBehaviour.Properties#ofFullCopy and BlockBehaviour.Properties#ofLegacyCopy will copy over the required features.
// This means that in 1.21.3, using BlockBehaviour.Properties.ofFullCopy(Blocks.PALE_OAK_WOOD) would have your block require the 'WINTER_DROP' flag.
DeferredBlock<Block> EXPERIMENTAL_BLOCK = BLOCKS.registerSimpleBlock("experimental", BlockBehaviour.Properties.of()
    .requiredFeatures(EXPERIMENTAL) // mark as requiring the 'EXPERIMENTAL' flag
);

// BlockItems are special in that the required features are inherited from their respective Blocks.
// The same is also true for spawn eggs and their respective EntityTypes.
DeferredItem<BlockItem> EXPERIMENTAL_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(EXPERIMENTAL_BLOCK);

// EntityType
DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, "examplemod");
DeferredHolder<EntityType<?>, EntityType<ExperimentalEntity>> EXPERIMENTAL_ENTITY = ENTITY_TYPES.register("experimental", registryName -> EntityType.Builder.of(ExperimentalEntity::new, MobCategory.AMBIENT)
    .requiredFeatures(EXPERIMENTAL) // mark as requiring the 'EXPERIMENTAL' flag
    .build(ResourceKey.create(Registries.ENTITY_TYPE, registryName))
);

// MenuType
DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, "examplemod");
DeferredHolder<MenuType<?>, MenuType<ExperimentalMenu>> EXPERIMENTAL_MENU = MENU_TYPES.register("experimental", () -> new MenuType<>(
    // Using vanilla's MenuSupplier:
    // This is used when your menu is not encoding complex data during `player.openMenu`. Example:
    // (windowId, inventory) -> new ExperimentalMenu(windowId, inventory),

    // Using NeoForge's IContainerFactory:
    // This is used when you wish to read complex data encoded during `player.openMenu`.
    // Casting is important here, as `MenuType` specifically expects a `MenuSupplier`.
    (IContainerFactory<ExperimentalMenu>) (windowId, inventory, buffer) -> new ExperimentalMenu(windowId, inventory, buffer),
    
    FeatureFlagSet.of(EXPERIMENTAL) // mark as requiring the 'EXPERIMENTAL' flag
));

// MobEffect
DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, "examplemod");
DeferredHolder<MobEffect, ExperimentalMobEffect> EXPERIMENTAL_MOB_EEFECT = MOB_EFFECTS.register("experimental", registryName -> new ExperimentalMobEffect(MobEffectCategory.NEUTRAL, CommonColors.WHITE)
    .requiredFeatures(EXPERIMENTAL) // mark as requiring the 'EXPERIMENTAL' flag
);

// Potion
DeferredRegister<Potion> POTIONS = DeferredRegister.create(Registries.POTION, "examplemod");
DeferredHolder<Potion, ExperimentalPotion> EXPERIMENTAL_POTION = POTIONS.register("experimental", registryName -> new ExperimentalPotion(registryName.toString(), new MobEffectInstance(EXPERIMENTAL_MOB_EEFECT))
    .requiredFeatures(EXPERIMENTAL) // mark as requiring the 'EXPERIMENTAL' flag
);

// GameRule
DeferredRegister<GameRule> GAME_RULES = DefeferredRegister.create(Registries.GAME_RULE, "examplemod");
DeferredHolder<GameRule, GameRule> EXPERIMENTAL_GAME_RULE = GAME_RULES.register("experimental", registryName -> new GameRule(
    GameRuleCategory.MISC, GameRuleType.BOOL, BoolArgumentType.bool(), GameRuleTypeVisitor::visitBoolean, Codec.BOOL, bool -> bool ? 1 : 0, false,
    FeatureFlagSet.of(EXPERIMENTAL) // mark as requiring the 'EXPERIMENTAL' flag
));
```

### Validating Enabled Status

In order to validate if features should be enabled or not, you must first acquire the set of enabled features. This can be done in a variety of ways, but the common and recommended method is `LevelReader#enabledFeatures`.  

```java
level.enabledFeatures(); // from a 'LevelReader' instance
entity.level().enabledFeatures(); // from a 'Entity' instance

// Client Side
minecraft.getConnection().enabledFeatures();

// Server Side
server.getWorldData().enabledFeatures();
```

To validate if any `FeatureFlagSet` is enabled, you can pass the enabled features to `FeatureFlagSet#isSubsetOf`, and for validating if a specific `FeatureElement` is enabled, you can invoke `FeatureElement#isEnabled`.

:::note
`ItemStack` has a special `isItemEnabled(FeatureFlagSet)` method. This is so that empty stacks are treated as enabled even if the required features for the backing `Item` do not match the enabled features. It is recommended to prefer this method over `Item#isEnabled` where possible.
:::

```java
requiredFeatures.isSubsetOf(enabledFeatures);
featureElement.isEnabled(enabledFeatures);
itemStack.isItemEnabled(enabledFeatures);
```

## Feature Packs

_See also: [Resource Packs](../resources/index.md#assets), [Data Packs](../resources/index.md#data) and [Pack.mcmeta](../resources/index.md#packmcmeta)_

Feature packs are a type of pack that not only loads resources and/or data, but also has the ability to toggle on a given set of feature flags. These flags are defined in the `pack.mcmeta` JSON file at the root of this pack, which follows the below format:

:::note
This file differs from the one in your mod's `resources/` directory. This file defines a brand new feature pack and thus must be in its own folder.
:::

```json5
{
    "features": {
        "enabled": [
            // Identifier of a Feature flag to be enabled
            // Must be a valid registered flag
            "examplemod:experimental"
        ]
    },
    "pack": { /*...*/ }
}
```

There are a couple of ways for users to obtain a feature pack, namely installing them from an external source as a datapack, or downloading a mod that has a built-in feature pack. Both of these then need to be installed differently depending on the [physical side](../concepts/sides.md).

### Built-In

Built-in packs are bundled with your mod and are made available to the game using the `AddPackFindersEvent` event.

```java
@SubscribeEvent // on the mod event bus
public static void addFeaturePacks(final AddPackFindersEvent event) {
    event.addPackFinders(
            // Path relative to your mods 'resources' pointing towards this pack
            // Take note this also defines your packs id using the following format
            // mod/<namespace>:<path>`, e.g. `mod/examplemod:data/examplemod/datapacks/experimental`
            Identifier.fromNamespaceAndPath("examplemod", "data/examplemod/datapacks/experimental"),
            
            // What kind of resources are contained within this pack
            // 'CLIENT_RESOURCES' for packs with client assets (resource packs)
            // 'SERVER_DATA' for packs with server data (data packs)
            PackType.SERVER_DATA,
            
            // Display name shown in the Experiments screen
            Component.literal("ExampleMod: Experiments"),
            
            // In order for this pack to load and enable feature flags, this MUST be 'FEATURE',
            // any other PackSource type is invalid here
            PackSource.FEATURE,
            
            // If this is true, the pack is always active and cannot be disabled, should always be false for feature packs
            false,
            
            // Priority to load resources from this pack in
            // 'TOP' this pack will be prioritized over other packs
            // 'BOTTOM' other packs will be prioritized over this pack 
            Pack.Position.TOP
    );
}
```

#### Enabling in Singleplayer

1. Create a new world.
2. Navigate to the Experiments screen.
3. Toggle on the desired packs.
4. Confirm changes by clicking `Done`.

#### Enabling in Multiplayer

1. Open your server's `server.properties` file.
2. Add the feature pack id to `initial-enabled-packs`, separating each pack by a `,`. The pack id is defined during registering your pack finder, as seen above.

### External

External packs are provided to your users in datapack form.

#### Installation in Singleplayer

1. Create a new world.
2. Navigate to the datapack selection screen.
3. Drag and drop the datapack zip file onto the game window.
4. Move the newly available datapack over to the `Selected` packs list.
5. Confirm changes by clicking `Done`.

The game will now warn you about any newly selected experimental features, potential bugs, issues and crashes. You can confirm these changes by clicking `Proceed` or `Details` to see an extensive list of all selected packs and which features they would enable.

:::note
External feature packs do not show up in the Experiments screen. The Experiments screen will only show built-in feature packs.

To disable external feature packs after enabling them, navigate back into the datapacks screen and move the external packs back into `Available` from `Selected`.
:::

#### Installation in Multiplayer

Enabling Feature Packs can only be done during initial world creation, and they cannot be disabled once enabled.

1. Create the directory `./world/datapacks`
2. Upload the datapack zip file into the newly created directory
3. Open your server's `server.properties` file
4. Add the datapack zip file name (excluding `.zip`) to `initial-enabled-packs` (separating each pack by a `,`)
   - Example: The zip `examplemod-experimental.zip` would be added like so `initial-enabled-packs=vanilla,examplemod-experimental`

### Data Generation

_See also: [Datagen](../resources/index.md#data-generation)_

Feature packs can be generated during regular mod datagen. This is best used in combination with built-in packs, but it is also possible to zip up the generated result and share it as an external pack. Just choose one, i.e. don't provide it as an external pack and also bundle it as a built-in pack.

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(final GatherDataEvent.Client event) {
    DataGenerator generator = event.getGenerator();
    
    // To generate a feature pack, you must first obtain a pack generator instance for the desired pack.
    // generator.getBuiltinDatapack(<shouldGenerate>, <namespace>, <path>);
    // This will generate the feature pack into the following path:
    // ./data/<namespace>/datapacks/<path>
    PackGenerator featurePack = generator.getBuiltinDatapack(true, "examplemod", "experimental");
        
    // Register a provider to generate the `pack.mcmeta` file.
    featurePack.addProvider(output -> PackMetadataGenerator.forFeaturePack(
            output,
            
            // Description displayed in the Experiments screen
            Component.literal("Enabled experimental features for ExampleMod"),
            
            // Set of Feature flags this pack should enable
            FeatureFlagSet.of(EXPERIMENTAL)
    ));
    
    // Register additional providers (recipes, loot tables) to `featurePack` to write any generated resources into this pack, rather than the root pack.
}
```

## networking/configuration-tasks

---
sidebar_position: 3
---
# Using Configuration Tasks

The networking protocol for the client and server has a specific phase where the server can configure the client before the player actually joins the game. This phase is called the configuration phase, and is for example used by the vanilla server to send the resource pack information to the client.

This phase can also be used by mods to configure the client before the player joins the game.

## Registering a configuration task

The first step to using the configuration phase is to register a configuration task. This can be done by registering a new configuration task in the `RegisterConfigurationTasksEvent` event.

```java
@SubscribeEvent // on the mod event bus
public static void register(final RegisterConfigurationTasksEvent event) {
    event.register(new MyConfigurationTask());
}
```

The `RegisterConfigurationTasksEvent` event is fired on the mod bus, and exposes the current listener used by the server to configure the relevant client. A modder can use the exposed listener to figure out if the client is running the mod, and if so, register a configuration task.

## Implementing a configuration task

A configuration task is a simple interface: `ICustomConfigurationTask`. This interface has two methods: `void run(Consumer<CustomPacketPayload> sender);`, and `ConfigurationTask.Type type();` which returns the type of the configuration task. The type is used to identify the configuration task. An example of a configuration task is shown below:

```java
public record MyConfigurationTask implements ICustomConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(Identifier.fromNamespaceAndPath("mymod", "my_task"));
    
    @Override
    public void run(final Consumer<CustomPacketPayload> sender) {
        final MyData payload = new MyData();
        sender.accept(payload);
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
```

## Acknowledging a configuration task

Your configuration is executed on the server, and the server needs to know when the next configuration task can be executed. This is done by acknowledging the execution of said configuration task.

There are two primary ways of achieving this:

### Capturing the listener

When the client does not need to acknowledge the configuration task, then the listener can be captured, and the configuration task can be acknowledged directly on the server side.

```java
public record MyConfigurationTask(ServerConfigurationPacketListener listener) implements ICustomConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(Identifier.fromNamespaceAndPath("mymod", "my_task"));
    
    @Override
    public void run(final Consumer<CustomPacketPayload> sender) {
        final MyData payload = new MyData();
        sender.accept(payload);
        this.listener().finishCurrentTask(this.type());
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
```

To use such a configuration task, the listener needs to be captured in the `RegisterConfigurationTasksEvent` event.

```java
@SubscribeEvent // on the mod event bus
public static void register(final RegisterConfigurationTasksEvent event) {
    event.register(new MyConfigurationTask(event.getListener()));
}
```

Then the next configuration task will be executed immediately after the current configuration task has completed, and the client does not need to acknowledge the configuration task. Additionally, the server will not wait for the client to properly process the send payloads.

### Acknowledging the configuration task

When the client needs to acknowledge the configuration task, then you will need to send your own payload to the client:

```java
public record AckPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AckPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mymod", "ack"));
    
    // Unit codec with no data to write
    public static final StreamCodec<ByteBuf, AckPayload> STREAM_CODEC = StreamCodec.unit(new AckPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

When a payload from a server side configuration task is properly processed you can send this payload to the server to acknowledge the configuration task.

```java
public void onMyData(MyData data, IPayloadContext context) {
    context.enqueueWork(() -> {
        blah(data.name());
    })
    .exceptionally(e -> {
        // Handle exception
        context.disconnect(Component.translatable("my_mod.configuration.failed", e.getMessage()));
        return null;
    })
    .thenAccept(v -> {
        context.reply(new AckPayload());
    });     
}
```

Where `onMyData` is the handler for the payload that was sent by the server side configuration task.

When the server receives this payload it will acknowledge the configuration task, and the next configuration task will be executed:

```java
public void onAck(AckPayload payload, IPayloadContext context) {
    context.finishCurrentTask(MyConfigurationTask.TYPE);
}
```

Where `onAck` is the handler for the payload that was sent by the client.

## Stalling the login process

When the configuration is not acknowledged, then the server will wait forever, and the client will never join the game. So it is important to always acknowledge the configuration task, unless the configuration task failed, then you can disconnect the client.

## networking/index

# Networking

Communication between servers and clients is the backbone of a successful mod implementation.

There are two primary goals in network communication:

1. Making sure the client view is "in sync" with the server view
    - The flower at coordinates (X, Y, Z) just grew
1. Giving the client a way to tell the server that something has changed about the player
    - the player pressed a key

The most common way to accomplish these goals is to pass messages between the client and the server. These messages will usually be structured, containing data in a particular arrangement, for easy sending and receiving.

There is a technique provided by NeoForge to facilitate communication mostly built on top of [netty]. This technique can be used by listening for the `RegisterPayloadHandlersEvent` event, and then registering a specific type of [payloads], its reader, and its handler function to the registrar.

[netty]: https://netty.io "Netty Website"
[payloads]: payload.md "Registering custom Payloads"

## networking/payload

---
sidebar_position: 1
---
# Registering Payloads

Payloads are a way to send arbitrary data between the client and the server. They are registered using the `PayloadRegistrar` from the `RegisterPayloadHandlersEvent` event.

```java
@SubscribeEvent // on the mod event bus
public static void register(RegisterPayloadHandlersEvent event) {
    // Sets the current network version
    final PayloadRegistrar registrar = event.registrar("1");
}
```

Assuming we want to send the following data:

```java
public record MyData(String name, int age) {}
```

Then we can implement the `CustomPacketPayload` interface to create a payload that can be used to send and receive this data.

```java
public record MyData(String name, int age) implements CustomPacketPayload {
    
    public static final CustomPacketPayload.Type<MyData> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mymod", "my_data"));

    // Each pair of elements defines the stream codec of the element to encode/decode and the getter for the element to encode
    // 'name' will be encoded and decoded as a string
    // 'age' will be encoded and decoded as an integer
    // The final parameter takes in the previous parameters in the order they are provided to construct the payload object
    public static final StreamCodec<ByteBuf, MyData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8,
        MyData::name,
        ByteBufCodecs.VAR_INT,
        MyData::age,
        MyData::new
    );
    
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

As you can see from the example above the `CustomPacketPayload` interface requires us to implement the `type` method. The `type` method is responsible for returning a unique identifier for this payload. We then also need a reader to register this later on with the `StreamCodec` to read and write the payload data.

Finally, we can register this payload with the registrar:

```java
// In some common event class

@SubscribeEvent // on the mod event bus
public static void register(RegisterPayloadHandlersEvent event) {
    final PayloadRegistrar registrar = event.registrar("1");
    registrar.playBidirectional(
        MyData.TYPE,
        MyData.STREAM_CODEC,
        ServerPayloadHandler::handleDataOnMain
    );
}

// In some client-only event class

@SubscribeEvent // on the mod event bus only on the physical client
public static void register(RegisterClientPayloadHandlersEvent event) {
    event.register(
        MyData.TYPE,
        ClientPayloadHandler::handleDataOnMain
    );
}
```

Dissecting the code above we can notice a couple of things:
- The registrar has `play*` methods, that can be used for registering payloads which are sent during the play phase of the game.
    - Not visible in this code are the methods `configuration*` and `common*`; however, they can also be used to register payloads for the configuration phase. The `common` method can be used to register payloads for both the configuration and play phase simultaneously.
- The registrar uses a `*Bidirectional` method, that can be used for registering payloads which are sent to both the logical server and logical client.
    - Not visible in this code are the methods `*ToClient` and `*ToServer`; however, they can also be used to register payloads to only the logical client or only the logical server, respectively.
- The type of the payload is used as a unique identifier for the payload.
- The [stream codec][streamcodec] is used to read and write the payload to and from the buffer sent across the network
- The payload handler is a callback for when the payload arrives on one of the logical sides.
    - If a `*ToServer` method is used, the payload handler will be the last parameter in the method.
    - If a `*ToClient` method is used, the payload handler will need to be registered via `RegisterClientPayloadHandlersEvent`, passing in the payload type and the handler.
    - If a `*Bidirectional` method is used, a payload handler will need to use both.

:::note
The client payload handler has its own event `RegisterClientPayloadHandlersEvent` to protect against code reaching across both logical and physical [sides].
:::

Now that we have registered the payload we need to implement a handler. For this example we will specifically take a look at the client side handler, however the server side handler is very similar.

```java
public class ClientPayloadHandler {
    
    public static void handleDataOnMain(final MyData data, final IPayloadContext context) {
        // Do something with the data, on the main thread
        blah(data.age());
    }
}
```

Here a couple of things are of note:

- The handling method here gets the payload, and a contextual object.
- The handling method of the payload is, by default, invoked on the main thread.

If you need to do some computation that is resource intensive, then the work should be done on the network thread, instead of blocking the main thread. This is done by setting the `HandlerThread` of the `PayloadRegistrar` to `HandlerThread#NETWORK` via `PayloadRegistrar#executesOn` before registering the payload for serverbound connections, and by passing in the `HandlerThread` to `RegisterClientPayloadHandlersEvent#register` for clientbound connections.

```java
// In some common event class

@SubscribeEvent // on the mod event bus
public static void register(RegisterPayloadHandlersEvent event) {
    final PayloadRegistrar registrar = event.registrar("1")
        .executesOn(HandlerThread.NETWORK); // All subsequent payloads will register on the network thread
    registrar.playBidirectional(
        MyData.TYPE,
        MyData.STREAM_CODEC,
        ServerPayloadHandler::handleDataOnNetwork
    );
}

// In some client-only event class

@SubscribeEvent // on the mod event bus only on the physical client
public static void register(RegisterClientPayloadHandlersEvent event) {
    event.register(
        MyData.TYPE,
        HandlerThread.NETWORK // Payload handler will be invoked on the network thread
        ClientPayloadHandler::handleDataOnNetwork
    );
}
```

:::note
All payloads registered after an `executesOn` call will retain the same thread execution location until `executesOn` is called again.

```java
PayloadRegistrar registrar = event.registrar("1");

registrar.playBidirectional(...); // On the main thread
registrar.playBidirectional(...); // On the main thread

// Configuration methods modify the state of the registrar
// by creating a new instance, so the change needs to be
/// updated by storing the result
registrar = registrar.executesOn(HandlerThread.NETWORK);

registrar.playBidirectional(...); // On the network thread
registrar.playBidirectional(...); // On the network thread

registrar = registrar.executesOn(HandlerThread.MAIN);

registrar.playBidirectional(...); // On the main thread
registrar.playBidirectional(...); // On the main thread
```
:::

Here a couple of things are of note:

- If you want to run code on the main game thread you can use `enqueueWork` to submit a task to the main thread.
    - The method will return a `CompletableFuture` that will be completed on the main thread.
    - Notice: A `CompletableFuture` is returned, this means that you can chain multiple tasks together, and handle exceptions in a single place.
    - If you do not handle the exception in the `CompletableFuture` then it will be swallowed, **and you will not be notified of it**.

```java
public class ClientPayloadHandler {
    
    public static void handleDataOnNetwork(final MyData data, final IPayloadContext context) {
        // Do something with the data, on the network thread
        blah(data.name());
        
        // Do something with the data, on the main thread
        context.enqueueWork(() -> {
            blah(data.age());
        })
        .exceptionally(e -> {
            // Handle exception
            context.disconnect(Component.translatable("my_mod.networking.failed", e.getMessage()));
            return null;
        });
    }
}
```

With your own payloads you can then use those to configure the client and server using [Configuration Tasks][configuration].

## Sending Payloads

`CustomPacketPayload`s are sent across the network using vanilla's packet system by wrapping the payload via `ServerboundCustomPayloadPacket` when sending to the server, or `ClientboundCustomPayloadPacket` when sending to the client. Payloads sent to the client can only contain at most 1 MiB of data while payloads to the server can only contain less than 32 KiB. 

All payloads are sent via `Connection#send` with some level of abstraction; however, it is generally inconvenient to call these methods if you want to send packets to multiple people based on a given condition. Therefore, `PacketDistributor` contains a number of convenience implementations to send payloads to the client, and `ClientPacketDistributor` contains one method to send packets to the server (`sendToServer`).

```java
// ON THE CLIENT

// Send payload to server
ClientPacketDistributor.sendToServer(new MyData(...));

// ON THE SERVER

// Send to one player (ServerPlayer serverPlayer)
PacketDistributor.sendToPlayer(serverPlayer, new MyData(...));

/// Send to all players tracking this chunk (ServerLevel serverLevel, ChunkPos chunkPos)
PacketDistributor.sendToPlayersTrackingChunk(serverLevel, chunkPos, new MyData(...));

/// Send to all connected players
PacketDistributor.sendToAllPlayers(new MyData(...));
```

See the `PacketDistributor` and `ClientPacketDistributor` classes for more implementations.

[configuration]: configuration-tasks.md
[sides]: ../concepts/sides.md
[streamcodec]: streamcodecs.md

## networking/streamcodecs

---
sidebar_position: 2
---
# Stream Codecs

Stream codecs are a serialization tool used to describe how an object should be stored and read from a stream, such as buffers. Stream codecs are primarily used by Vanilla's [networking system][networking] to sync data.

:::info
As stream codecs are roughly analogous to [codecs], this page has been formatted in the same way to show the similarities.
:::

## Using Stream Codecs

Stream codecs encode and decode objects into some stream using `StreamCodec#encode` and `StreamCodec#decode`, respectively. `encode` takes in the stream and the object to encode into the stream. `decode` takes in the stream and returns the decoded object. Typically, the stream is either a `ByteBuf`, `FriendlyByteBuf`, or `RegistryFriendlyByteBuf`.

```java
// Let exampleStreamCodec represent a StreamCodec<ExampleJavaObject>
// Let exampleObject be a ExampleJavaObject
// Let buffer be a RegistryFriendlyByteBuf

// Encode Java object into the buffer stream
exampleStreamCodec.encode(buffer, exampleObject);

// Read Java object from buffer stream
ExampleJavaObject obj = exampleStreamCodec.decode(buffer);
```

:::note
Unless you are manually handling the buffer object, you will generally never call `encode` and `decode`.
:::

## Existing Stream Codecs

### `ByteBufCodecs`

`ByteBufCodecs` contains static instances of codecs for certain primitives and objects.

| Stream Codec   | Java Type     |
|----------------|---------------|
| `BOOL`         | `Boolean`     |
| `BYTE`         | `Byte`        |
| `SHORT`        | `Short`       |
| `INT`          | `Integer`     |
| `LONG`         | `Long`        |
| `FLOAT`        | `Float`       |
| `DOUBLE`       | `Double`      |
| `BYTE_ARRAY`   | `byte[]`\*    |
| `LONG_ARRAY`   | `long[]`      |
| `STRING_UTF8`  | `String`\*\*  |
| `TAG`          | `Tag`         |
| `COMPOUND_TAG` | `CompoundTag` |
| `VECTOR3F`     | `Vector3fc`   |
| `QUATERNIONF`  | `Quaternionfc`|
| `GAME_PROFILE` | `GameProfile` |

\* `byte[]` can be limited to a certain number of values via `ByteBufCodecs#byteArray`.

\*\* `String` can be limited to a certain number of characters via `ByteBufCodecs#stringUtf8`.

Additionally, there are some static instances that encode and decode primitives and objects using a different method.

#### Unsigned Shorts

`UNSIGNED_SHORT` is an alternative of `SHORT` that is meant to be treated as an unsigned number. As numbers are signed in Java, unsigned shorts are sent and received as `Integer`s with the upper two bytes masked out.

#### Variable-Sized Number

`VAR_INT` and `VAR_LONG` are stream codecs where the value is encoded to be as small as possible. This is done by encoding seven bits at a time, using the upper bit as a marker of whether there is more data for this number. Numbers between 0 and 2^28-1 for integers or 0 and 2^56-1 for longs will be sent shorter or equal to the number of bytes in a integer or long, respectively. If the values of your numbers are normally in this range and generally at the lower end of it, then these variable stream codecs should be used.

:::note
`VAR_INT` and `VAR_LONG` are alternatives for `INT` and `LONG`, respectively.
:::

#### Trusted Tags

`TRUSTED_TAG` and `TRUSTED_COMPOUND_TAG` are variants of `TAG` and `COMPOUND_TAG`, respectively, that have an unlimited heap to decode the tag to, compared to the 2MiB limit of `TAG` and `COMPOUND_TAG`. Trusted tag stream codecs should ideally only be used in clientbound packets, such as what Vanilla does for [block entity data packet][blockentity] and [entity data serializers][entity].

If a different limit should be used, then a `NbtAccounter` can be supplied with the given size using `ByteBufCodecs#tagCodec` or `#compoundTagCodec`. An optional-wrapped `Tag` can also be obtained using `#optionalTagCodec`.

#### Lenient JSON

`ByteBufCodecs#lenientJson` handles an arbitrary `JsonElement`, allowing for multiple floating-point descriptors like `nan` or `infinite`, or multiple top-level objects. It takes in the maximum size the JSON can be.

### Vanilla and NeoForge

Minecraft and NeoForge define many stream codecs for objects that are frequently encoded and decoded. Some examples include `Identifier#STREAM_CODEC` for `Identifier`s or `NeoForgeStreamCodecs#CHUNK_POS` for `ChunkPos`s.

Most of the stream codecs can be found within the object class itself or within `StreamCodec`, `ByteBufCodecs`, or `NeoForgeStreamCodecs`. 

## Creating Stream Codecs

Stream codecs can be created for reading or writing any object to a stream. This documentation will focus on the stream as a buffer as that is its primary purpose.

Stream codecs have two generics: `B` representing the buffer and `V` representing the object value. `B` is generally one of three types: `ByteBuf`, `FriendlyByteBuf`, `RegistryFriendlyByteBuf`, each extending one another. `FriendlyByteBuf` adds Minecraft-specific read and write methods while `RegistryFriendlyByteBuf` provides access to the list of registries and its objects.

When constructing a stream codec, `B` should be the least-specific buffer type. For example, a `Identifier` is sent as a string. As strings are supported by a regular `ByteBuf`, its type should be `StreamCodec<ByteBuf, Identifier>`. `FriendlyByteBuf` contains methods for writing a `ChunkPos`, so its type should be `StreamCodec<FriendlyByteBuf, ChunkPos>`. An `Item` needs access to the registry, so its type should be `StreamCodec<RegistryFriendlyByteBuf, Item>`.

Most methods that take in a stream codec look for `? super B` for the buffer type, meaning that all three of the above examples can be used if the buffer type is a `RegistryFriendlyByteBuf`.

### Member Encoders

`StreamMemberEncoder` is an alternative to `StreamEncoder` where the encoding object comes first and the buffer second. This is typically used when the encoding object contains an instance method to write the object to the buffer. A `StreamMemberEncoder` can be used to create the `StreamCodec` by calling `StreamCodec#ofMember`.

```java
// Some object to create a stream codec for
public class ExampleObject {
    
    // The normal constructor
    public ExampleObject(String arg1, int arg2, boolean arg3) { /* ... */ }

    // The stream decoder reference
    public ExampleObject(ByteBuf buffer) { /* ... */ }

    // The stream encoder reference
    public void encode(ByteBuf buffer) { /* ... */ }
}

// What the stream codec would look like
public static StreamCodec<ByteBuf, ExampleObject> STREAM_CODEC =
    StreamCodec.ofMember(ExampleObject::encode, ExampleObject::new);
```

### Composites

Stream codecs can read and write objects via `StreamCodec#composite`. Each composite stream codec defines a list of stream codecs and getters which are read/written in the order they are provided. `composite` has overloads up to twelve parameters.

Every two parameters in a `composite` represents the stream codec used to read/write the field and a getter to get the field to encode from the object. The final parameter is a function to create a new instance of the object when decoding.

```java
// Objects to create a stream codec for
public record SimpleExample(String arg1, int arg2, boolean arg3) {}
public record RegistryExample(double arg1, Holder<Item> arg2) {}

// The stream codecs
public static final StreamCodec<ByteBuf, SimpleExample> SIMPLE_STREAM_CODEC =
    StreamCodec.composite(
        // Stream codec and getter pair
        ByteBufCodecs.STRING_UTF8, SimpleExample::arg1,
        ByteBufCodecs.VAR_INT, SimpleExample::arg2,
        ByteBufCodecs.BOOL, SimpleExample::arg3,
        SimpleExample::new
    );

// Since this has a holder, a RegistryFriendlyByteBuf is used
public static final StreamCodec<RegistryFriendlyByteBuf, RegistryExample> REGISTRY_STREAM_CODEC =
    StreamCodec.composite(
        // Note that ByteBuf stream codecs can be used here
        ByteBufCodecs.DOUBLE, RegistryExample::arg1,
        ByteBufCodecs.holderRegistry(Registries.ITEM), RegistryExample::arg2,
        RegistryExample::new
    );
```

### Transformers

Stream codecs can be transformed into equivalent, or partially equivalent, representations using mapping methods. Two mapping methods apply to the value while one mapping method applies to the buffer.

The `map` method transforms the value using two functions: one to transform the current type into the new type, and one to transform the new type back into the current type. This is analogous to [codec transformers][transformers].

```java
public static final StreamCodec<ByteBuf, Identifier> STREAM_CODEC = 
    ByteBufCodecs.STRING_UTF8.map(
        // String -> Identifier
        Identifier::new,
        // Identifier -> String
        Identifier::toString
    );
```

The `apply` method transforms the value using a `StreamCodec.CodecOperation`. A `StreamCodec.CodecOperation` takes in a stream codec of the current type and returns a stream codec of the new type. These typically wrap around `map` or take in helper methods.

```java
public static final StreamCodec<ByteBuf, List<Identifier>> STREAM_CODEC =
    Identifier.STREAM_CODEC.apply(ByteBufCodecs.list());
```

The `mapStream` method transforms the buffer using a function that takes in the new buffer type and returns the current buffer type. This method should rarely be used as most methods with stream codecs do not need to change the type of the buffer.

```java
public static final StreamCodec<RegistryFriendlyByteBuf, Integer> STREAM_CODEC =
    ByteBufCodecs.VAR_INT.mapStream(buffer -> (ByteBuf) buffer);
```

### Unit

A stream codec which supplies an in-code value and encodes to nothing can be represented using `StreamCodec#unit`. This is useful if no information should be synced across the network.

:::warning
Unit stream codecs expect that any encoded object must match the unit specified; otherwise an error will be thrown. Therefore, all objects must have some `equals` implementation that returns true for the unit object, or that the instance provided to the stream codec is always provided when encoding.
:::

```java
public static final StreamCodec<ByteBuf, Item> UNIT_STREAM_CODEC =
    StreamCodec.unit(Items.AIR);
```
### Lazy Initialized

Sometimes, a stream codec may rely on data that is not present when it is constructed. In these situations `NeoForgeStreamCodecs#lazy` can be used for a stream codec to construct itself on first read/write. The method takes in a supplied stream codec.

```java
public static final StreamCodec<ByteBuf, Item> LAZY_STREAM_CODEC = 
    NeoForgeStreamCodecs.lazy(
        () -> StreamCodec.unit(Items.AIR)
    );
```

### Collections

A stream codec for collections can be generated from a object stream codec via `collection`. `collection` takes in an `IntFunction` that constructs the empty collection, a stream codec of the object, and an optional maximum size.

```java
public static final StreamCodec<ByteBuf, Set<BlockPos>> COLLECTION_STREAM_CODEC =
    ByteBufCodecs.collection(
        HashSet::new, // Constructs a set with the specified capacity
        BlockPos.STREAM_CODEC,
        256 // The set can only have up to 256 elements
    );
```

Another overload of `collection` can be specified with `StreamCodec#apply`.

```java
public static final StreamCodec<ByteBuf, Set<BlockPos>> COLLECTION_STREAM_CODEC =
    BlockPos.STREAM_CODEC.apply(
        ByteBufCodecs.collection(HashSet::new)
    );
```

List-based collections also can be specified through `StreamCodec#apply` by calling `ByteBufCodecs#list` with an optional maximum size.

```java
public static final StreamCodec<ByteBuf, List<BlockPos>> LIST_STREAM_CODEC =
    BlockPos.STREAM_CODEC.apply(
        // The list can only have up to 256 elements
        ByteBufCodecs.list(256)
    );
```

### Map

A stream codec for a map of key and value objects can be generated using two stream codecs via `ByteBufCodecs#map`. The function also takes in an `IntFunction` that constructs the empty map and an optional maximum size.

```java
public static final StreamCodec<ByteBuf, Map<String, BlockPos>> MAP_STREAM_CODEC =
    ByteBufCodecs.map(
        HashMap::new, // Constructs a map with the specified capacity
        ByteBufCodecs.STRING_UTF8,
        BlockPos.STREAM_CODEC,
        256 // The map can only have up to 256 elements
    );
```

### Either

A stream codec for two different methods of reading/writing some object data can be generated from two stream codecs via `ByteBufCodecs#either`. This method first reads/writes a boolean indicating whether to read/write the first or second stream codec, respectively.

```java
public static final StreamCodec<ByteBuf, Either<Integer, String>> EITHER_STREAM_CODEC = 
    ByteBufCodecs.either(
        ByteBufCodecs.VAR_INT,
        ByteBufCodecs.STRING_UTF8
    );
```

### Id Mapper

In most cases, when sending information across the network where an object is present on both sides, an integer representing an id is sent. Ids representing an object reduce the amount of information that need to be synced across the network. Both enums and registries make use of this.

`ByteBufCodecs#idMapper` provides a convenient way to send ids for objects. It either takes in two functions which convert an object to int and vice versa, or an `IdMap`.

```java
// For some enum
public enum ExampleIdObject {
    ;

    // Gets Id -> Enum
    public static final IntFunction<ExampleIdObject> BY_ID = 
        ByIdMap.continuous(
            ExampleIdObject::getId,
            ExampleIdObject.values(),
            ByIdMap.OutOfBoundsStrategy.ZERO
    );
    
    ExampleIdObject(int id) { /* ... */ }
}

// The stream codec would look like
public static final StreamCodec<ByteBuf, ExampleIdObject> ID_STREAM_CODEC =
    ByteBufCodecs.idMapper(ExampleIdObject.BY_ID, ExampleIdObject::getId);
```

### Optional

A stream codec for sending an `Optional` wrapped value can be generated by supplying a stream codec to `ByteBufCodecs#optional`. This method first reads/writes a boolean indicating whether to read/write the object.

```java
public static final StreamCodec<RegistryFriendlyByteBuf, Optional<DataComponentType<?>>> OPTIONAL_STREAM_CODEC =
    DataComponentType.STREAM_CODEC.apply(ByteBufCodecs::optional);
```

### Registry Objects

Registry objects can be sent across the network using one of three methods: `registry`, `holderRegistry`, or `holder`. Each takes in a `ResourceKey` representing the registry the registry object is in.

:::warning
Custom registries must be syncable by calling `RegistryBuilder#sync` and setting the value to `true`. Otherwise, the encoder will throw an exception.
:::

`registry` and `holderRegistry` returns the registry object or a holder wrapped registry object, respectively. These methods send over an id representing the registry object.

```java
// Registry object
public static final StreamCodec<RegistryFriendlyByteBuf, Item> VALUE_STREAM_CODEC =
    ByteBufCodecs.registry(Registries.ITEM);

// Holder of registry object
public static final StreamCodec<RegistryFriendlyByteBuf, Holder<Item>> HOLDER_STREAM_CODEC =
    ByteBufCodecs.holderRegistry(Registries.ITEM);
```

`holder` returns a holder wrapped registry object. This method sends over an id representing the registry object, or the registry object itself if the provided `Holder` is a direct reference. To do so, `holder` also takes in the stream codec of the registry object.

```java
public static final StreamCodec<RegistryFriendlyByteBuf, Holder<SoundEvent>> STREAM_CODEC =
    ByteBufCodecs.holder(
        Registries.SOUND_EVENT, SoundEvent.DIRECT_STREAM_CODEC
    );
```

:::note
`holder` will only throw an exception for a non-synced custom registry if the holder is not direct.
:::

### Holder Sets

Tags or sets of holder wrapped registry objects can be sent using `holderSet`. This takes in a `ResourceKey` representing the registry the registry objects are in.

```java
public static final StreamCodec<RegistryFriendlyByteBuf, HolderSet<Item>> HOLDER_SET_STREAM_CODEC =
    ByteBufCodecs.holderSet(Registries.ITEM);
```

### Recursive

Sometimes, an object may reference an object of the same type as a field. For example, `MobEffectInstance` takes in an optional `MobEffectInstance` if there is a hidden effect. In this case, `StreamCodec#recursive` can be used to supply the stream codec as part of a function to create the stream codec.

```java
// Define our recursive object
public record RecursiveObject(Optional<RecursiveObject> inner) { /* ... */ }

public static final StreamCodec<ByteBuf, RecursiveObject> RECURSIVE_CODEC = StreamCodec.recursive(
    recursedStreamCodec -> StreamCodec.composite(
        recursedStreamCodec.apply(ByteBufCodecs::optional),
        RecursiveObject::inner,
        RecursiveObject::new
    )
);
```

### Dispatch

Stream codecs can have sub-stream codecs that can decode a particular object based on some specified type via `StreamCodec#dispatch`. This is typically used with registry objects that represent a type, like `ParticleType` for `ParticleOptions` or `StatType` for `Stat`s.

A dispatch stream codec first attempts to read/write the type object. From there, the current object is read/written using one of the functions provided in the method. The first `Function` takes in the current object and gets the type to write the value. The second `Function` takes in the type object and gets the `StreamCodec` for the current object to read the value.

```java
// Define our object(s)
public abstract class ExampleObject {

    // Define the method used to specify the object type for encoding
    public abstract StreamCodec<? super RegistryFriendlyByteBuf, ? extends ExampleObject> streamCodec();
}

// Assume there is a ResourceKey<StreamCodec<? super RegistryFriendlyByteBuf, ? extends ExampleObject>> DISPATCH
public static final StreamCodec<RegistryFriendlyByteBuf, ExampleObject> DISPATCH_STREAM_CODEC =
    ByteBufCodecs.registry(DISPATCH).dispatch(
        // Get the stream codec from the specific object
        ExampleObject::streamCodec,
        // Get the stream codec from the registry object
        Function.identity()
    );
```

[networking]: payload.md
[codecs]: ../datastorage/codecs.md
[blockentity]: ../blockentities/index.md#syncing-on-block-update
[entity]: ../entities/data.md
[transformers]: ../datastorage/codecs.md#transformers