# NeoForge 数据与服务器资源

> 来源:neoforged/Documentation 官方文档(英文原文,API 名与代码签名原样保留)。
> 回答时用中文解释,类名/方法名保持英文。

## datastorage/attachments

---
sidebar_position: 4
---
# Data Attachments

The data attachment system allows mods to attach and store additional data on block entities, chunks, entities, and levels.

_To store additional level data, you can use [SavedData][saveddata]._

:::note
Data attachments for item stacks have been superceeded by vanilla's [data components][datacomponents].
:::

## Creating an attachment type

To use the system, you need to register an `AttachmentType`. The attachment type contains the following configuration:

- A default value supplier to create the instance when the data is first accessed.
- An optional serializer if the attachment should be persisted.
- (If a serializer was configured) The `copyOnDeath` flag to automatically copy entity data on death (see below).

:::tip
If you don't want your attachment to persist, do not provide a serializer.
:::

There are a few ways to provide an attachment serializer: directly implementing `IAttachmentSerializer`, implementing [`ValueIOSerializable`][valueio] and using the static `AttachmentType#serializable` method to create the builder, or providing a map codec to the builder.

In any case, the attachment **must be registered** to the `NeoForgeRegistries.ATTACHMENT_TYPES` registry. Here is an example:

```java
// Create the DeferredRegister for attachment types
private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MOD_ID);

// Serialization via ValueIOSerializable
private static final Supplier<AttachmentType<ItemStacksResourceHandler>> HANDLER = ATTACHMENT_TYPES.register(
    "handler", () -> AttachmentType.serializable(() -> new ItemStacksResourceHandler(1)).build()
);
// Serialization via map codec
private static final Supplier<AttachmentType<Integer>> MANA = ATTACHMENT_TYPES.register(
    "mana", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("mana")).build()
);
// No serialization
private static final Supplier<AttachmentType<SomeCache>> SOME_CACHE = ATTACHMENT_TYPES.register(
    "some_cache", () -> AttachmentType.builder(() -> new SomeCache()).build()
);

// In your mod constructor, don't forget to register the DeferredRegister to your mod bus:
ATTACHMENT_TYPES.register(modBus);
```

## Using the attachment type

Once the attachment type is registered, it can be used on any holder object. Calling `getData` if no data is present will attach a new default instance.

```java
// Get the ItemStacksResourceHandler if it already exists, else attach a new one:
ItemStacksResourceHandler handler = chunk.getData(HANDLER);
// Get the current player mana if it is available, else attach 0:
int playerMana = player.getData(MANA);
// And so on...
```

If attaching a default instance is not desired, a `hasData` check can be added:

```java
// Check if the chunk has the HANDLER attachment before doing anything.
if (chunk.hasData(HANDLER)) {
    ItemStacksResourceHandler handler = chunk.getData(HANDLER);
    // Do something with chunk.getData(HANDLER).
}
```

The data can also be updated with `setData`:

```java
// Increment mana by 10.
player.setData(MANA, player.getData(MANA) + 10);
```

:::important
Usually, block entities and chunks need to be marked as dirty when they are modified (with `setChanged` and `setUnsaved(true)`). This is done automatically for calls to `setData`:

```java
chunk.setData(MANA, chunk.getData(MANA) + 10); // will call setUnsaved automatically
```

but if you modify some data that you obtained from `getData` (including a newly created default instance) then you must mark block entities and chunks as dirty explicitly:

```java
var mana = chunk.getData(MUTABLE_MANA);
mana.set(10);
chunk.setUnsaved(true); // must be done manually because we did not use setData
```
:::

## Sharing data with the client

To sync block entity, chunk, level, or entity attachments to a client, you can implement `sync` in the builder. Attachments are then sent to the client when the attachment is default-created through `AttachmentHolder#getData`, updated through `AttachmentHolder#setData`, or removed through `AttachmentHolder#removeData`. If the data should be sent at other times, then `AttachmentHolder#syncData` can be called with the `AttachmentType` to sync.

`AttachmentType.Builder#sync` has three overloads; however, they each create an `AttachmentSyncHandler<T>`, where `T` is the type of the data attachment. The handler has three methods: two to `read` and `write` to the network, and one to determine whether a given player can see the data broadcasted by the holder (`sendToPlayer`). The sync handler is ignored if the data attachment is removed.

```java
public class ExampleSyncHandler implements AttachmentSyncHandler<ExampleData> {

    @Override
    public void write(RegistryFriendlyByteBuf buf, ExampleData attachment, boolean initialSync) {
        // Write the attachment data to the buffer
        // If `initialSync` is true, you should write the entire attachment as the client does not have any prior data
        // If `initialSync` is false, you can choose to only write the data you would like to update
        
        // Example:
        if (initialSync) {
            // Write entire attachment
            ExampleData.STREAM_CODEC.encode(buf, attachment);
        } else {
            // Write update data
        }
    }

    @Override
    @Nullable
    public ExampleData read(IAttachmentHolder holder, RegistryFriendlyByteBuf buf, @Nullable ExampleData previousValue) {
        // Read the data from the buffer and return the new data attachment
        // `previousValue` is `null` if there was no prior data on the client
        // The result should return `null` if the data attachment should be removed

        // Example:
        if (previousValue == null) {
            // Read entire attachment
            return ExampleData.STREAM_CODEC.decode(buf);
        } else {
            // Read update data and merge to previous value
            return previousValue;
        }
    }

    @Override
    public boolean sendToPlayer(IAttachmentHolder holder, ServerPlayer to) {
        // Return whether the holder data is synced to the given player client
        // The players checked are different depending on the attachment holder:
        // - Block entities: All players tracking the chunk the block entity is within
        // - Chunk: All players tracking the chunk
        // - Entity: All players tracking the current entity, includes the current player if they are the attachment holder
        // - Level: All players in the current dimension / level

        // Example:
        // Only send the attachment if they are the attachment holder
        return holder == to;
    }
}
```

The two other overloads which delegate to `AttachmentSyncHandler` take in a [`StreamCodec`][streamcodec] for `read` and `write`, and an optional predicate for `sendToPlayer`.

```java
// Assume ExampleData has some stream codec STREAM_CODEC

// Sync handler
public static final Supplier<AttachmentType<ExampleData>> WITH_SYNC_HANDLER = ATTACHMENT_TYPES.register(
    "with_sync_handler", () -> AttachmentType.builder(() -> new ExampleData())
        .sync(new ExampleSyncHandler())
        .build()
);

// Stream codec
public static final Supplier<AttachmentType<ExampleData>> WITH_STREAM_CODEC = ATTACHMENT_TYPES.register(
    "with_stream_codec", () -> AttachmentType.builder(() -> new ExampleData())
        .sync(ExampleData.STREAM_CODEC)
        .build()
);

// Stream codec with predicate
public static final Supplier<AttachmentType<ExampleData>> WITH_PREDICATE = ATTACHMENT_TYPES.register(
    "with_predicate", () -> AttachmentType.builder(() -> new ExampleData())
        .sync((holder, to) -> holder == to, ExampleData.STREAM_CODEC)
        .build()
);
```

:::note
Using the `StreamCodec` overloads means that the entire data attachment will be synced each time, ignoring any data that was previously on the client.
:::

## Copying data on player death

By default, [entity] data attachments are not copied on player death. To automatically copy an attachment on player death, set `copyOnDeath` in the attachment builder.

More complex handling can be implemented via `PlayerEvent.Clone` by reading the data from the original entity and assigning it to the new entity. In this event, the `#isWasDeath` method can be used to distinguish between respawning after death and returning from the End. This is important because the data will already exist when returning from the End, so care has to be taken to not duplicate values in this case.

For example:

```java
@SubscribeEvent // on the game event bus
public static void onClone(PlayerEvent.Clone event) {
    if (event.isWasDeath() && event.getOriginal().hasData(MY_DATA)) {
        event.getEntity().getData(MY_DATA).fieldToCopy = event.getOriginal().getData(MY_DATA).fieldToCopy;
    }
}
```

[datacomponents]: ../items/datacomponents.md
[entity]: ../entities/index.md
[saveddata]: saveddata.md
[streamcodec]: ../networking/streamcodecs.md
[valueio]: valueio.md#valueioserializable

## datastorage/codecs

---
sidebar_position: 2
---
# Codecs

Codecs are a serialization tool from Mojang's [DataFixerUpper] used to describe how objects can be transformed between different formats, such as `JsonElement`s for JSON and `Tag`s for NBT.

## Using Codecs

Codecs are primarily used to encode, or serialize, Java objects to some data format type and decode, or deserialize, formatted data objects back to its associated Java type. This is typically accomplished using `Codec#encodeStart` and `Codec#parse`, respectively.

### DynamicOps

To determine what intermediate file format to encode and decode to, both `#encodeStart` and `#parse` require a `DynamicOps` instance to define the data within that format.

The [DataFixerUpper] library contains `JsonOps` to codec JSON data stored in [`Gson`'s][gson] `JsonElement` instances. `JsonOps` supports two versions of `JsonElement` serialization: `JsonOps#INSTANCE` which defines a standard JSON file, and `JsonOps#COMPRESSED` which allows data to be compressed into a single string.

```java
// Let exampleCodec represent a Codec<ExampleJavaObject>
// Let exampleObject be a ExampleJavaObject
// Let exampleJson be a JsonElement

// Encode Java object to regular JsonElement
exampleCodec.encodeStart(JsonOps.INSTANCE, exampleObject);

// Encode Java object to compressed JsonElement
exampleCodec.encodeStart(JsonOps.COMPRESSED, exampleObject);

// Decode JsonElement into Java object
// Assume JsonElement was parsed normally
exampleCodec.parse(JsonOps.INSTANCE, exampleJson);
```

Minecraft also provides `NbtOps` to codec NBT data stored in `Tag` instances. This can be referenced using `NbtOps#INSTANCE`.

```java
// Let exampleCodec represent a Codec<ExampleJavaObject>
// Let exampleObject be a ExampleJavaObject
// Let exampleNbt be a Tag

// Encode Java object to Tag
exampleCodec.encodeStart(NbtOps.INSTANCE, exampleObject);

// Decode Tag into Java object
exampleCodec.parse(NbtOps.INSTANCE, exampleNbt);
```

To handle registry entries, Minecraft provides `RegistryOps`, which contains a lookup provider to get available registry elements. These can be created by `RegistryOps#create` that takes in the `DynamicOps` with the specific type to store the data within and the lookup provider containing access to the available registries. NeoForge extends `RegistryOps` to create `ConditionalOps`: a registry codec lookup that can handle [conditions to load the entry][conditions].

```java
// Let lookupProvider be a HolderLookup.Provider
// Let exampleCodec represent a Codec<ExampleJavaObject>
// Let exampleObject be a ExampleJavaObject
// Let exampleJson be a JsonElement

// Get the registry ops for JsonElement
RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookupProvider);

// Encode Java object to JsonElement
exampleCodec.encodeStart(ops, exampleObject);

// Decode JsonElement into Java object
exampleCodec.parse(ops, exampleJson);
```

#### Format Conversion

`DynamicOps` can also be used separately to convert between two different encoded formats. This can be done using `#convertTo` and supplying the `DynamicOps` format and the encoded object to convert.

```java
// Convert Tag to JsonElement
// Let exampleTag be a Tag
JsonElement convertedJson = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, exampleTag);
```

### DataResult

Encoded or decoded data using codecs return a `DataResult` which holds the converted instance or some error data depending on whether the conversion was successful. When the conversion is successful, the `Optional` supplied by `#result` will contain the successfully converted object. If the conversion fails, the `Optional` supplied by `#error` will contain the `PartialResult`, which holds the error message and a partially converted object depending on the codec.

Additionally, there are many methods on `DataResult` that can be used to transform the result or error into the desired format. For example, `#resultOrPartial` will return an `Optional` containing the result on success, and the partially converted object on failure. The method takes in a string consumer to determine how to report the error message if present.

```java
// Let exampleCodec represent a Codec<ExampleJavaObject>
// Let exampleJson be a JsonElement

// Decode JsonElement into Java object
DataResult<ExampleJavaObject> result = exampleCodec.parse(JsonOps.INSTANCE, exampleJson);

result
    // Get result or partial on error, report error message
    .resultOrPartial(errorMessage -> /* Do something with error message */)
    // If result or partial is present, do something
    .ifPresent(decodedObject -> /* Do something with decoded object */);
```

## Existing Codecs

### Primitives

The `Codec` class contains static instances of codecs for certain defined primitives.

Codec         | Java Type
:---:         | :---
`BOOL`        | `Boolean`
`BYTE`        | `Byte`
`SHORT`       | `Short`
`INT`         | `Integer`
`LONG`        | `Long`
`FLOAT`       | `Float`
`DOUBLE`      | `Double`
`STRING`      | `String`\*
`BYTE_BUFFER` | `ByteBuffer`
`INT_STREAM`  | `IntStream`
`LONG_STREAM` | `LongStream`
`PASSTHROUGH` | `Dynamic<?>`\*\*
`EMPTY`       | `Unit`\*\*\*

\* `String` can be limited to a certain number of characters via `Codec#string` or `Codec#sizeLimitedString`.

\*\* `Dynamic` is an object which holds a value encoded in a supported `DynamicOps` format. These are typically used to convert encoded object formats into other encoded object formats.

\*\*\* `Unit` is an object used to represent `null` objects.

### Vanilla and NeoForge

Minecraft and NeoForge define many codecs for objects that are frequently encoded and decoded. Some examples include `Identifier#CODEC` for `Identifier`s, `ExtraCodecs#INSTANT_ISO8601` for `Instant`s in the `DateTimeFormatter#ISO_INSTANT` format, and `CompoundTag#CODEC` for `CompoundTag`s.

:::caution
`CompoundTag`s cannot decode lists of numbers from JSON using `JsonOps`. `JsonOps`, when converting, sets a number to its most narrow type. `ListTag`s force a specific type for its data, so numbers with different types (e.g. `64` would be `byte`, `384` would be `short`) will throw an error on conversion.
:::

Vanilla and NeoForge registries also have codecs for the type of object the registry contains (e.g. `BuiltInRegistries#BLOCK` have a `Codec<Block>`). `Registry#byNameCodec` will encode the registry object to their registry name. Vanilla registries also have a `Registry#holderByNameCodec` which encodes to a registry name and decodes to the registry object wrapped in a `Holder`.

## Creating Codecs

Codecs can be created for encoding and decoding any object. For understanding purposes, the equivalent encoded JSON will be shown.

### Records

Codecs can define objects through the use of records. Each record codec defines any object with explicit named fields. There are many ways to create a record codec, but the simplest is via `RecordCodecBuilder#create`.

`RecordCodecBuilder#create` takes in a function which defines an `Instance` and returns an application (`App`) of the object. A correlation can be drawn to creating a class *instance* and the constructors used to *apply* the class to the constructed object.

```java
// Some object to create a codec for
public class SomeObject {

    public SomeObject(String s, int i, boolean b) { /* ... */ }

    public String s() { /* ... */ }

    public int i() { /* ... */ }

    public boolean b() { /* ... */ }
}
```

#### Fields

An `Instance` can define up to 16 fields using `#group`. Each field must be an application defining the instance the object is being made for and the type of the object. The simplest way to meet this requirement is by taking a `Codec`, setting the name of the field to decode from, and setting the getter used to encode the field.

A field can be created from a `Codec` using `#fieldOf`, if the field is required, or `#optionalFieldOf`, if the field is wrapped in an `Optional` or defaulted. Either method requires a string containing the name of the field in the encoded object. The getter used to encode the field can then be set using `#forGetter`, taking in a function which given the object, returns the field data.

:::warning
`#optionalFieldOf` will throw an error if there is an element that throws an error when parsing. If the error should be consumed, use `#lenientOptionalFieldOf` instead.
:::

From there, the resulting product can be applied via `#apply` to define how the instance should construct the object for the application. For ease of convenience, the grouped fields should be listed in the same order they appear in the constructor such that the function can simply be a constructor method reference.

```java
public static final Codec<SomeObject> RECORD_CODEC = RecordCodecBuilder.create(instance -> // Given an instance
    instance.group( // Define the fields within the instance
        Codec.STRING.fieldOf("s").forGetter(SomeObject::s), // String
        Codec.INT.optionalFieldOf("i", 0).forGetter(SomeObject::i), // Integer, defaults to 0 if field not present
        Codec.BOOL.fieldOf("b").forGetter(SomeObject::b) // Boolean
    ).apply(instance, SomeObject::new) // Define how to create the object
);
```

```json5
// Encoded SomeObject
{
    "s": "value",
    "i": 5,
    "b": false
}

// Another encoded SomeObject
{
    "s": "value2",
    // i is omitted, defaults to 0
    "b": true
}

// Another encoded SomeObject
{
    "s": "value2",
    // Will throw an error as lenientOptionalFieldOf is not used
    "i": "bad_value",
    "b": true
}
```

### Transformers

Codecs can be transformed into equivalent, or partially equivalent, representations through mapping methods. Each mapping method takes in two functions: one to transform the current type into the new type, and one to transform the new type back to the current type. This is done through the `#xmap` function.

```java
// A class
public class ClassA {

    public ClassB toB() { /* ... */ }
}

// Another equivalent class
public class ClassB {

    public ClassA toA() { /* ... */ }
}

// Assume there is some codec A_CODEC
public static final Codec<ClassB> B_CODEC = A_CODEC.xmap(ClassA::toB, ClassB::toA);
```

If a type is partially equivalent, meaning that there are some restrictions during conversion, there are mapping functions which return a `DataResult` which can be used to return an error state whenever an exception or invalid state is reached.

Is A Fully Equivalent to B | Is B Fully Equivalent to A | Transform Method
:---:                      | :---:                      | :---
Yes                        | Yes                        | `#xmap`
Yes                        | No                         | `#flatComapMap`
No                         | Yes                        | `#comapFlatMap`
No                         | No                         | `#flatXMap`

```java
// Given an string codec to convert to a integer
// Not all strings can become integers (A is not fully equivalent to B)
// All integers can become strings (B is fully equivalent to A)
public static final Codec<Integer> INT_CODEC = Codec.STRING.comapFlatMap(
    s -> { // Return data result containing error on failure
        try {
            return DataResult.success(Integer.valueOf(s));
        } catch (NumberFormatException e) {
            return DataResult.error(s + " is not an integer.");
        }
    },
    Integer::toString // Regular function
);
```

```json5
// Will return 5
"5"

// Will error, not an integer
"value"
```

#### Range Codecs

Range codecs are an implementation of `#flatXMap` which returns an error `DataResult` if the value is not inclusively between the set minimum and maximum. The value is still provided as a partial result if outside the bounds. There are implementations for integers, floats, and doubles via `#intRange`, `#floatRange`, and `#doubleRange` respectively.

```java
public static final Codec<Integer> RANGE_CODEC = Codec.intRange(0, 4); 
```

```json5
// Will be valid, inside [0, 4]
4

// Will error, outside [0, 4]
5
```

#### String Resolver

`Codec#stringResolver` is an implementation of `flatXmap` which maps a string to some kind of object.

```java
public record StringResolverObject(String name) { /* ... */ }

// Assume there is some Map<String, StringResolverObject> OBJECT_MAP
public static final Codec<StringResolverObject> STRING_RESOLVER_CODEC = Codec.stringResolver(StringResolverObject::name, OBJECT_MAP::get);
```

```json5
// Will map this string to its associated object
"example_name"
```

### Defaults

If the result of encoding or decoding fails, a default value can be supplied instead via `Codec#orElse` or `Codec#orElseGet`.

```java
public static final Codec<Integer> DEFAULT_CODEC = Codec.INT.orElse(
    errorMessage -> /* Do something with the error message */,
    0 // Can also be a supplied value via #orElseGet
); 
```

```json5
// Not an integer, defaults to 0
"value"
```

### Unit

A codec which supplies an in-code value and encodes to nothing can be represented using `MapCodec#unitCodec`. This is useful if a codec uses a non-encodable entry within the data object.

```java
public static final Codec<IEventBus> UNIT_CODEC = MapCodec.unitCodec(
    () -> NeoForge.EVENT_BUS // Can also be a raw value
);
```

```json5
// Nothing here, will return the NeoForge event bus
```

### Lazy Initialized

Sometimes, a codec may rely on data that is not present when it is constructed. In these situations `Codec#lazyInitialized` can be used to for a codec to construct itself on first encoding/decoding. The method takes in a supplied codec.

```java
public static final Codec<IEventBus> LAZY_CODEC = Codec.lazyInitialized(
    () -> MapCodec.unitCodec(NeoForge.EVENT_BUS)
);
```

```json5
// Nothing here, will return the NeoForge event bus
// Encodes/decodes the same way as the normal codec
```

### List

A codec for a list of objects can be generated from an object codec via `Codec#listOf`. `listOf` can also take in integers representing the minimum and maximum size of the list. `sizeLimitedListOf` does the same but only specifies a maximum bound.

```java
// BlockPos#CODEC is a Codec<BlockPos>
public static final Codec<List<BlockPos>> LIST_CODEC = BlockPos.CODEC.listOf();
```

```json5
// Encoded List<BlockPos>
[
    [1, 2, 3], // BlockPos(1, 2, 3)
    [4, 5, 6], // BlockPos(4, 5, 6)
    [7, 8, 9]  // BlockPos(7, 8, 9)
]
```

List objects decoded using a list codec are stored in an **immutable** list. If a mutable list is needed, a [transformer] should be applied to the list codec.

### Map

A codec for a map of keys and value objects can be generated from two codecs via `Codec#unboundedMap`. Unbounded maps can specify any string-based or string-transformed value to be a key.

```java
// BlockPos#CODEC is a Codec<BlockPos>
public static final Codec<Map<String, BlockPos>> MAP_CODEC = Codec.unboundedMap(Codec.STRING, BlockPos.CODEC);
```

```json5
// Encoded Map<String, BlockPos>
{
    "key1": [1, 2, 3], // key1 -> BlockPos(1, 2, 3)
    "key2": [4, 5, 6], // key2 -> BlockPos(4, 5, 6)
    "key3": [7, 8, 9]  // key3 -> BlockPos(7, 8, 9)
}
```

Map objects decoded using a unbounded map codec are stored in an **immutable** map. If a mutable map is needed, a [transformer] should be applied to the map codec.

:::caution
Unbounded maps only support keys that encode/decode to/from strings. A key-value [pair] list codec can be used to get around this restriction.
:::

### Pair

A codec for pairs of objects can be generated from two codecs via `Codec#pair`.

A pair codec decodes objects by first decoding the left object in the pair, then taking the remaining part of the encoded object and decodes the right object from that. As such, the codecs must either express something about the encoded object after decoding (such as [records]), or they have to be augmented into a `MapCodec` and transformed into a regular codec via `#codec`. This can typically done by making the codec a [field] of some object.

```java
public static final Codec<Pair<Integer, String>> PAIR_CODEC = Codec.pair(
    Codec.INT.fieldOf("left").codec(),
    Codec.STRING.fieldOf("right").codec()
);
```

```json5
// Encoded Pair<Integer, String>
{
    "left": 5,       // fieldOf looks up 'left' key for left object
    "right": "value" // fieldOf looks up 'right' key for right object
}
```

:::tip
A map codec with a non-string key can be encoded/decoded using a list of key-value pairs applied with a [transformer].
:::

### Either

A codec for two different methods of encoding/decoding some object data can be generated from two codecs via `Codec#either`.

An either codec attempts to decode the object using the first codec. If it fails, it attempts to decode using the second codec. If that also fails, then the `DataResult` will only contain the error from the second codec failure.

```java
public static final Codec<Either<Integer, String>> EITHER_CODEC = Codec.either(
    Codec.INT,
    Codec.STRING
);
```

```json5
// Encoded Either.Left<Integer, String>
5

// Encoded Either.Right<Integer, String>
"value"
```

:::tip
This can be used in conjunction with a [transformer] to get a specific object from two different methods of encoding.
:::

#### Xor

`Codec#xor` is a special case of the [either] codec where a result is only successful if one of the two methods are processed successfully. If both codecs can be processed, then an error is thrown instead.

```java
public static final Codec<Either<Integer, String>> XOR_CODEC = Codec.xor(
    Codec.INT.fieldOf("number").codec(),
    Codec.STRING.fieldOf("text").codec()
);
```

```json5
// Encoded Either.Left<Integer, String>
{
    "number": 4
}

// Encoded Either.Right<Integer, String>
{
    "text": "value"
}

// Throws an error as both can be decoded
{
    "number": 4,
    "text": "value"
}
```

#### Alternative

`Codec#withAlternative` is a special case of the [either] codec where both codecs are trying to decode the same object, but stored in a different format. The first, or primary, codec will attempt to decode the object. On failure, the second codec will be used instead. Encoding will always use the primary codec.

```java
public static final Codec<BlockPos> ALTERNATIVE_CODEC = Codec.withAlternative(
    BlockPos.CODEC,
    RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("x").forGetter(BlockPos::getX),
        Codec.INT.fieldOf("y").forGetter(BlockPos::getY),
        Codec.INT.fieldOf("z").forGetter(BlockPos::getZ)
    ), BlockPos::new)
);
```

```json5
// Normal method to decode BlockPos
[ 1, 2, 3 ]

// Alternative method to decode BlockPos
{
    "x": 1,
    "y": 2,
    "z": 3
}
```

### Recursive

Sometimes, an object may reference an object of the same type as a field. For example, `EntityPredicate` takes in an `EntityPredicate` for the vehicle, passenger, and targeted entity. In this case, `Codec#recursive` can be used to supply the codec as part of a function to create the codec.

```java
// Define our recursive object
public record RecursiveObject(Optional<RecursiveObject> inner) { /* ... */ }

public static final Codec<RecursiveObject> RECURSIVE_CODEC = Codec.recursive(
    RecursiveObject.class.getSimpleName(), // This is for the toString method
    recursedCodec -> RecordCodecBuilder.create(instance -> instance.group(
        recursedCodec.optionalFieldOf("inner").forGetter(RecursiveObject::inner)
    ).apply(instance, RecursiveObject::new))
);
```

```json5
// An encoded recursive object
{
    "inner": {
        "inner": {}
    }
}
```

### Dispatch

Codecs can have subcodecs which can decode a particular object based upon some specified type via `Codec#dispatch`. This is typically used in registries which contain codecs, such as rule tests or block placers.

A dispatch codec first attempts to get the encoded type from some string key (usually `type`). From there, the type is decoded, calling a getter for the specific codec used to decode the actual object. If the `DynamicOps` used to decode the object compresses its maps, or the object codec itself is not augmented into a `MapCodec` (such as records or fielded primitives), then the object needs to be stored within a `value` key. Otherwise, the object is decoded at the same level as the rest of the data.

```java
// Define our object
public abstract class ExampleObject {

    // Define the method used to specify the object type for encoding
    public abstract MapCodec<? extends ExampleObject> type();
}

// Create simple object which stores a string
public class StringObject extends ExampleObject {

    public StringObject(String s) { /* ... */ }

    public String s() { /* ... */ }

    public MapCodec<? extends ExampleObject> type() {
        // A registered registry object
        // "string":
        //   Codec.STRING.xmap(StringObject::new, StringObject::s).fieldOf("string")
        return STRING_OBJECT_CODEC.get();
    }
}

// Create complex object which stores a string and integer
public class ComplexObject extends ExampleObject {

    public ComplexObject(String s, int i) { /* ... */ }

    public String s() { /* ... */ }

    public int i() { /* ... */ }

    public MapCodec<? extends ExampleObject> type() {
        // A registered registry object
        // "complex":
        //   RecordCodecBuilder.mapCodec(instance ->
        //     instance.group(
        //       Codec.STRING.fieldOf("s").forGetter(ComplexObject::s),
        //       Codec.INT.fieldOf("i").forGetter(ComplexObject::i)
        //     ).apply(instance, ComplexObject::new)
        //   )
        return COMPLEX_OBJECT_CODEC.get();
    }
}

// Assume there is an Registry<MapCodec<? extends ExampleObject>> DISPATCH
public static final Codec<ExampleObject> = DISPATCH.byNameCodec() // Gets Codec<MapCodec<? extends ExampleObject>>
    .dispatch(
        ExampleObject::type, // Get the codec from the specific object
        Function.identity() // Get the codec from the registry
    );
```

```json5
// Simple object
{
    "type": "string", // For StringObject
    "value": "value" // Codec type is not augmented from MapCodec, needs field
}

// Complex object
{
    "type": "complex", // For ComplexObject

    // Codec type is augmented from MapCodec, can be inlined
    "s": "value",
    "i": 0
}
```

[DataFixerUpper]: https://github.com/Mojang/DataFixerUpper
[gson]: https://github.com/google/gson
[conditions]: ../resources/server/conditions.md
[transformer]: #transformer-codecs
[pair]: #pair
[records]: #records
[field]: #fields
[either]: #either

## datastorage/nbt

---
sidebar_position: 1
---
# Named Binary Tag (NBT)

NBT is a format introduced in the earliest days of Minecraft, written by Notch himself. It is widely used throughout the Minecraft codebase for data storage.

## Specification

The NBT spec is similar to the JSON spec, with a few differences:

- Distinct types for bytes, shorts, longs and floats exist, suffixed by `b`, `s`, `l` and `f`, respectively, similar to how they would be represented in Java code.
    - Doubles may also be suffixed with `d`, but this is not required, similar to Java code. The optional `i` suffix available in Java for integers is not permitted.
    - The suffixes are not case-sensitive. So for example, `64b` is the same as `64B`, and `0.5F` is the same as `0.5f`.
- Booleans do not exist, they are instead represented by bytes. `true` becomes `1b`, `false` becomes `0b`.
    - The current implementation treats all non-zero values as `true`, so `2b` would be treated as `true` as well.
- There is no `null` equivalent in NBT.
- Quotes around keys are optional. So a JSON property `"duration": 20` can become both `duration: 20` and `"duration": 20` in NBT.
- What is known in JSON as a sub-object is known in NBT as a **compound tag** (or just compound).
- NBT lists cannot mix and match types, unlike in JSON. The list type is determined by the first element, or defined in code.
    - However, lists of lists can mix and match different list types. So a list of two lists, where the first one is a list of strings and the second one is a list of bytes, is allowed.
- There are special **array** types that are different from lists, but follow their scheme of containing elements in square brackets. There are three array types:
    - Byte arrays, denoted by a `B;` at the beginning of the array. Example: `[B;0b,30b]`
    - Integer arrays, denoted by a `I;` at the beginning of the array. Example: `[I;0,-300]`
    - Long arrays, denoted by an `L;` at the beginning of the array. Example: `[L;0l,240l]`
- Trailing commas in lists, arrays and compound tags are allowed.

## NBT Files

Minecraft uses `.nbt` files extensively, for example for structure files in [datapacks][datapack]. Region files (`.mca`) that contain the contents of a region (i.e. a collection of chunks), as well as the various `.dat` files used in different places by the game, are NBT files as well.

NBT files are typically compressed with GZip. As such, they are binary files and cannot be edited directly.

## NBT in Code

Like in JSON, all NBT objects are children of an enclosing object. So let's create one:

```java
CompoundTag tag = new CompoundTag();
```

We can now put our data into that tag:

```java
tag.putInt("Color", 0xffffff);
tag.putString("Level", "minecraft:overworld");
tag.putDouble("IAmRunningOutOfIdeasForNamesHere", 1d);
```

Several helpers exist here, for example, `putIntArray` also has a convenience method that takes a `List<Integer>` in addition to the standard variant that takes an `int[]`.

Of course, we can also get values from that tag:

```java
Optional<Integer> color = tag.getInt("Color");
Optional<String> level = tag.getString("Level");
Optional<Double> d = tag.getDouble("IAmRunningOutOfIdeasForNamesHere");
```

As it is unknown whether the tag is present or not, the values returned are optional-wrapped. A default can be specified using one of the `*Or*` methods for primitive types. `ListTag`s can be defaulted via `getListOrEmpty` while `CompoundTag`s via `getCompoundOrEmpty`. Primitive array types have no `*Or*` equivalent.

```java
int color = tag.getIntOr("Color", 0xffffff);
String level = tag.getStringOr("Level", "minecraft:overworld");
double d = tag.getDoubleOr("IAmRunningOutOfIdeasForNamesHere", 1d);
```

All tag types implement the `Tag` interface. Most tag types besides `CompoundTag` are mostly internal, for example `ByteTag` or `StringTag`, though the direct `CompoundTag#get` and `#put` methods can work with them if you ever stumble across some.

There is one obvious exception, though: `ListTag`s. Working with these is special because these are associated with some tag type, computed internally:

```java
ListTag newList = new ListTag();
// Adds the tags to the list
newList.add(StringTag.valueOf("Value1"));
newList.add(StringTag.valueOf("Value2"));

// Getting the tag
ListTag getList = tag.getListOrEmpty("SomeListHere");
```

Finally, working with `CompoundTag`s inside other `CompoundTag`s directly utilizes `CompoundTag#get` and `#put`:

```java
tag.put("Tag", new CompoundTag());

// Can use regular `get` as well if you want to handle null case instead
tag.getCompoundOrEmpty("Tag");
```

## Usages of NBT

NBT is used in a lot of places in Minecraft. [`BlockEntity`s][blockentity]s and [`Entity`s][entity] abstract NBT usage into [value accesses][valueio]. `ItemStack`s abstract the usage into [data components][datacomponents].

## See Also

- [NBT Format on the Minecraft Wiki][nbtwiki]

[blockentity]: ../blockentities/index.md
[datapack]: ../resources/index.md#data
[datacomponents]: ../items/datacomponents.md
[entity]: ../entities/index.md
[nbtwiki]: https://minecraft.wiki/w/NBT_format
[valueio]: valueio.md

## datastorage/saveddata

---
sidebar_position: 5
---
# Saved Data

The Saved Data (SD) system can be used to save additional data on levels.

_If the data is specific to some block entities, chunks, or entities, consider using a [data attachment](attachments) instead._

## `SavedData`

Each SD implementation must subtype the `SavedData` class. This can be implemented like any other object, with your own fields and methods, but if you want to store the data or change to disk, then you must call `setDirty`. `setDirty` notifies the game that there are changes that need to be written. If not called, then the data will only persist as long as the current level (or world in case of the `MinecraftServer`) is loaded.

```java
// For some saved data implementation
public class ExampleSavedData extends SavedData {

    public void foo() {
        // Change data in saved data
        // Call set dirty if data changes
        this.setDirty();
    }
}
```

## `SavedDataType`

As the `SavedData` is simply an object, there needs to be some sort of associated identifier. Additionally, we also need to read and write the data to disk. This is where the `SavedDataType` comes in. It takes in the identifier of the saved data, a default constructor for when no data is present, and a [codec] used to encode and decode the data. The identifier is treated as the path location within the associated world folder and any level dimensions like so:

- `./<world_folder>/data/<identifier_namespace>/<identifier_path>.dat` for the server data
- `./<world_folder>/dimensions/<level_namespace>/<level_path>/data/<identifier_namespace>/<identifier_path>.dat` for individual level data

Any missing directories will be created, including those used as part of the identifier.

:::note
There is an additional fourth parameter for the `DataFixTypes`, but as NeoForge does not support data fixers, all vanilla use cases have been patched to allow null values.
:::

There are two variations of the `SavedDataType` constructor. The first takes in a simple `Supplier` for the constructor and a regular `Codec` for the disk handling. However, if you want to store the current `ServerLevel` or world seed, there is a NeoForge-added overload that takes in a `SavedDataType.Factory` for both, supplying a `ServerLevel`.

```java
// For some saved data implementation
public class NoContextExampleSavedData extends SavedData {

    public static final SavedDataType<NoContextExampleSavedData> ID = new SavedDataType<>(
        // The identifier of the saved data
        // Used as the path within the `data` folder
        Identifier.fromNamespaceAndPath("examplemod", "example"),
        // The initial constructor
        NoContextExampleSavedData::new,
        // The codec used to serialize the data
        RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("val1").forGetter(sd -> sd.val1),
            BuiltInRegistries.BLOCK.byNameCodec().fieldOf("val2").forGetter(sd -> sd.val2)
        ).apply(instance, NoContextExampleSavedData::new))
    );

    // Initial constructor
    public NoContextExampleSavedData() {
        // ...
    }

    // Data constructor
    public NoContextExampleSavedData(int val1, Block val2) {
        // ...
    }

    public void foo() {
        // Change data in saved data
        // Call set dirty if data changes
        this.setDirty();
    }
}

// For some saved data implementation
public class ContextExampleSavedData extends SavedData {

    public static final SavedDataType<ContextExampleSavedData> ID = new SavedDataType<>(
        // The identifier of the saved data
        // Used as the path within the `data` folder
        Identifier.fromNamespaceAndPath("examplemod", "example"),
        // The initial constructor
        ContextExampleSavedData::new,
        // The codec used to serialize the data
        level -> RecordCodecBuilder.create(instance -> instance.group(
            RecordCodecBuilder.point(level),
            Codec.INT.fieldOf("val1").forGetter(sd -> sd.val1),
            BuiltInRegistries.BLOCK.byNameCodec().fieldOf("val2").forGetter(sd -> sd.val2)
        ).apply(instance, ContextExampleSavedData::new))
    );

    // Initial constructor
    public ContextExampleSavedData(ServerLevel level) {
        // ...
    }

    // Data constructor
    public ContextExampleSavedData(ServerLevel level, int val1, Block val2) {
        // ...
    }

    public void foo() {
        // Change data in saved data
        // Call set dirty if data changes
        this.setDirty();
    }
}
```

## Attaching to a Level

Any `SavedData` is loaded and/or attached to a level or server dynamically. As such, if one is never created on a level or server, then it will not exist.

`SavedData`s are created and loaded from the `SavedDataStorage`, which can be accessed by calling either `ServerChunkCache#getDataStorage` or `ServerLevel#getDataStorage`. From there, you can get or create an instance of your SD by calling `SavedDataStorage#computeIfAbsent`, passing in the `SavedDataType`. This will attempt to get the current instance of the SD if present or create a new one and load all available data.

```java
// In some method with access to the SavedDataStorage
netherDataStorage.computeIfAbsent(ContextExampleSavedData.ID);
```

If a SD is not specific to a level, the SD should be attached to the `MinecraftServer` via `MinecraftServer#getDataStorage`.

[codec]: codecs.md

## datastorage/valueio

---
sidebar_position: 3
---
# Value I/O

The Value I/O system is a standardized serialization method to manipulate data of some backing object, such as [`CompoundTag`s for NBT][nbt].

## Inputs and Outputs

The Value I/O system is made up of two parts: a `ValueOutput` that writes to the object during serialization, and a `ValueInput` that reads from the object during deserialization. Implementing methods typically take in the `ValueOutput` or `ValueInput` as its only parameter, returning nothing. The value I/O expects the backing object to be a dictionary of string keys to object values. Using the provided methods, the value I/O then reads or writes information to the backing object.

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    // Write data to the output
}

@Override
protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);
    // Read data from the input
}

// For some Entity subclass
@Override
protected void addAdditionalSaveData(ValueOutput output) {
    super.addAdditionalSaveData(output);
    // Write data to the output
}

@Override
protected void readAdditionalSaveData(ValueInput input) {
    super.readAdditionalSaveData(input);
    // Read data from the input
}
```

### Primitives

Value I/O contains methods for reading and writing certain primitives. `ValueOutput` methods are prefixed with `put*`, taking in the key and the primitive value. `ValueInput` methods are named as `get*Or`, taking in the key and a default if none is present.

| Java Type | `ValueOutput` | `ValueInput`                 |
|:---------:|:-------------:|:----------------------------:|
| `boolean` | `putBoolean`  | `getBooleanOr`               |
| `byte`    | `putByte`     | `getByteOr`                  |
| `short`   | `putShort`    | `getShortOr`                 |
| `int`     | `putInt`      | `getInt`\*, `getIntOr`       |
| `long`    | `putLong`     | `getLong`\*, `getLongOr`     |
| `float`   | `putFloat`    | `getFloatOr`                 |
| `double`  | `putDouble`   | `getDoubleOr`                |
| `String`  | `putString`   | `getString`\*, `getStringOr` |
| `int[]`   | `putIntArray` | `getIntArray`\*              |

\* These `ValueInput` methods return an `Optional`-wrapped primitive instead of taking and passing back some fallback.

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    
    // Write data to the output
    output.putBoolean(
        // The string key
        "boolValue",
        // The value associated with this key
        true
    );
    output.putString("stringValue", "Hello world!");
}

@Override
protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);

    // Read data from the input

    // Defaults to false if not present
    boolean boolValue = input.getBooleanOr(
        // The string key to retrieve
        "boolValue",
        // The default value to return if the key is not present
        false
    );

    // Defaults to 'Dummy!' if not present
    String stringValue = input.getStringOr("stringValue", "Dummy!");
    // Returns an optional-wrapped value
    Optional<String> stringValueOpt = input.getString("stringValue");
}
```

### Codecs

[`Codec`s][codec] can also be used to store and read values from the value I/O. In vanilla, all `Codec`s are handled using a `RegistryOps`, allowing the storage of datapack entries. `ValueOutput#store` and `storeNullable` take in the key, the codec to write the object, and the object itself. `storeNullable` will not write anything if the object is `null`. `ValueInput#read` can read the object by taking in the key and the codec, returning an `Optional`-wrapped object.

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    
    // Write data to the output
    output.storeNullable("codecValue", Rarity.CODEC, Rarity.EPIC);
}

@Override
protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);

    // Read data from the input
    Optional<Rarity> codecValue = input.read("codecValue", Rarity.CODEC);
}
```

`ValueOutput` and `ValueInput` also provide a `store` / `read` method for `MapCodec`s. Compared to the `Codec`, the `MapCodec` variant merges the values onto the current root.

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    
    // Write data to the output
    output.store(
        SingleFile.MAP_CODEC,
        new SingleFile(Identifier.fromNamespaceAndPath("examplemod", "example"))
    );
}

@Override
protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);

    // Read data from the input

    // No key is needed as they are stored on the root value access
    Optional<SingleFile> file = input.read(SingleFile.MAP_CODEC);
    // This is present as `SingleFile` writes the `resource` parameter
    String resource = input.getStringOr("resource", "Not present!");
}
```

:::warning
The `MapCodec` will write any keys to the value access, potentially overwriting existing data. Make sure that any keys within the `MapCodec` are distinct from other keys.
:::

### Lists

Lists can be created and read from through one of two methods: child value I/Os or [`Codec`s].

A list is created via `ValueOutput#childrenList`, taking in some key. This returns a `ValueOutput.ValueOutputList`, which acts as a write-only list of value objects. A new value object can be added to the list via `ValueOutputList#addChild`. This returns a `ValueOutput` to write the value object data to. The list can then be read using `ValueInput#childrenList`, or `childrenListOrEmpty` to default to an empty list when not present. These methods return a `ValueInput.ValueInputList`, which acts as a read-only iterable or stream (via `stream`).

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    
    // Write data to the output

    // Create List
    ValueOutput.ValueOutputList listValue = output.childrenList("listValue");
    // Add elements
    ValueOutput childIdx0 = listValue.addChild();
    childIdx0.putBoolean("boolChild", false);
    ValueOutput childIdx1 = listValue.addChild();
    childIdx1.putInt("boolChild", true);
}

@Override
protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);

    // Read data from the input

    // Read values of list
    for (ValueInput childInput : input.childrenListOrEmpty("listValue")) {
        boolean boolChild = childInput.getBooleanOr("boolChild", false);
    }
}
```

`Codec`s provide a list variant for data objects via `ValueOutput#list`. This takes in a key and some `Codec`, returning a `ValueOutput.TypedOutputList`. A `TypedOutputList` is the same as `ValueOutputList`, except it operates on the data object instead of using another value I/O. Elements can be added to the list via `TypedOutputList#add`. Then, similarly, the list can then be read using `ValueInput#list` or `listOrEmpty`, returning a `TypedValueInput`.

:::note
The main difference between a `TypedValueOutput` / `TypedValueInput` and a `Codec#listOf` is how errors are handled. For a `Codec#listOf`, a failed entry will result in the entire object being marked as an error `DataResult`. Meanwhile, a typed value I/O handles the error typically through a `ProblemReporter`. In vanilla, `Codec#listOf` provides more flexibility since `ProblemReporter`s are specified when creating the value I/O. However, custom value I/O usage can implement either depending on the use case.
:::

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    
    // Write data to the output

    // Create List
    ValueOutput.TypedInputList<Rarity> listValue = output.list("listValue", Rarity.CODEC);
    // Add elements
    listValue.add(Rarity.COMMON);
    listValue.add(Rarity.EPIC);
}

@Override
protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);

    // Read data from the input

    // Read values of list
    for (Rarity rarity : input.listOrEmpty("listValue", Rarity.CODEC)) {
        // ...
    }
}
```

:::warning
Lists are still written to the `ValueOutput` even when empty. If you don't want to write the list, then the `TypedOutputList` or `ValueOutputList` should check if it `isEmpty`, then call `discard` with the list key.

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    
    // Write data to the output

    // Create List
    ValueOutput.TypedInputList<Rarity> listValue = output.list("listValue", Rarity.CODEC);
    
    // Check if list is empty
    if (listValue.isEmpty()) {
        // Discard from output
        output.discard("listValue");
    }
}
```
:::

### Objects

Objects can be created and read from via children. `ValueOutput#child` creates a new `ValueObject` given a key. Then, the object can be read using `ValueInput#child`, or `childOrEmpty` if it should default to an `ValueInput` with an empty backing value.

```java
// For some BlockEntity subclass
@Override
protected void saveAdditional(ValueOutput output) {
    super.saveAdditional(output);
    
    // Write data to the output

    // Create object
    ValueOutput objectValue = output.child("objectValue");
    // Add data to object
    objectValue.putBoolean("boolChild", true);
    objectValue.putInt("intChild", 20);
}

@Override
protected void loadAdditional(ValueInput input) {
    super.loadAdditional(input);

    // Read data from the input

    // Read object
    ValueInput objectValue = input.childOrEmpty("objectValue");
    // Get data from object
    boolean boolChild = objectValue.getBooleanOr("boolChild", false);
    int intChild = objectValue.getIntOr("intChild", 0);
}
```

## ValueIOSerializable

`ValueIOSerializable` is a NeoForge-added interface for objects that can be serialized and deserialized using value I/Os. NeoForge uses this API to handle [data attachments][attachments]. The interface provides two methods: `serialize` to write the object to a `ValueOutput`, and `deserialize` to read the object from a `ValueInput`.

```java
public class ExampleObject implements ValueIOSerializable {
    
    @Override
    public void serialize(ValueOutput output) {
        // Write the object data here
    }

    @Override
    public void deserialize(ValueInput input) {
        // Read the object data here
    }
}
```

`ValueIOSerializable` can also be written and read via the NeoForge-added methods `ValueOutputExtension#putChild` and `ValueInputExtension#readChild`.

## Implementations

### NBT

Value I/O for [NBTs][nbt] is handled via `TagValueOutput` and `TagValueInput`.

A `TagValueOutput` can be created via `createWithContext` or `createWithoutContext`, `createWithContext` means that the output has access to the `HolderLookup.Provider`, which provides the all registries entries (static and datapack), while `createWithoutContext` does not provide any datapack access. Vanilla only uses `createWithContext`. Once the `ValueOutput` has been used, the `CompoundTag` can be retrieved via `TagValueOutput#buildResult`. A `TagValueInput`, on the other hand, can be created via `create`, taking in the `HolderLookup.Provider` and the `CompoundTag` the input is accessing.

Both value I/Os also take in a `ProblemReporter`. The `ProblemReporter` is used to collect all internal errors during the read/write process. Currently, this only tracks `Codec` errors. How the errors are handled is up to the modder. Vanilla implementations throw if the `ProblemReporter` is not empty.

```java
// Assume we have access to a HolderLookup.Provider lookupProvider

TagValueOutput output = TagValueOutput.createWithContext(
    ProblemReporter.DISCARDING, // Choose to discard all errors
    lookupProvider
);

// Write to the output...

CompoundTag tag = output.buildResult();

// Collect the errors
ProblemReporter.Collector reporter = new ProblemReporter.Collector(
    // Optionally takes in the root path element
    // Some objects (e.g., block entities, entities) have a #problemPath() method that can be supplied
    new RootFieldPathElement("example_object")
);

TagValueInput input = TagValueInput.create(
    reporter,
    lookupProvider,
    tag
);

// Read from the input...
```

[attachments]: attachments.md
[codec]: codecs.md
[nbt]: nbt.md

## resources/index

# Resources

Resources are external files that are used by the game, but are not code. The most prominent kinds of resources are textures, however, many other types of resources exist in the Minecraft ecosystem. Of course, all these resources require a consumer on the code side, so the consuming systems are grouped in this section as well.

Minecraft generally has two kinds of resources: resources for the [logical client][logicalsides], known as assets, and resources for the [logical server][logicalsides], known as data. Assets are mostly display-only information, for example textures, display models, translations, or sounds, while data includes various things that affect gameplay, such as loot tables, recipes, or worldgen information. They are loaded from resource packs and data packs, respectively. NeoForge generates a built-in resource and data pack for every mod.

Both resource and data packs normally require a [`pack.mcmeta` file][packmcmeta]; however, modern NeoForge generates these at runtime for you, so you don't need to worry about it.

If you are confused about the format of something, have a look at the vanilla resources. Your NeoForge development environment not only contains vanilla code, but also vanilla resources. They can be found in the External Resources section (IntelliJ)/Project Libraries section (Eclipse), under the name `ng_dummy_ng.net.minecraft:client:client-extra:<minecraft_version>` (for Minecraft resources) or `ng_dummy_ng.net.neoforged:neoforge:<neoforge_version>` (for NeoForge resources).

## Assets

_See also: [Resource Packs][mcwikiresourcepacks] on the [Minecraft Wiki][mcwiki]_

Assets, or client-side resources, are all resources that are only relevant on the [client][sides]. They are loaded from resource packs, sometimes also known by the old term texture packs (stemming from old versions when they could only affect textures). A resource pack is basically an `assets` folder. The `assets` folder contains subfolders for the various namespaces the resource pack includes; every namespace is one subfolder. For example, a resource pack for a mod with the id `coolmod` will probably contain a `coolmod` namespace, but may additionally include other namespaces, such as `minecraft`.

NeoForge automatically collects all mod resource packs into the `Mod resources` pack, which sits at the bottom of the Selected Packs side in the resource packs menu. It is currently not possible to disable the `Mod resources` pack. However, resource packs that sit above the `Mod resources` pack override resources defined in a resource pack below them. This mechanic allows resource pack makers to override your mod's resources, and also allows mod developers to override Minecraft resources if needed.

Resource packs may contain folders with files affecting the following things:

| Folder Name      | Contents                                |
|------------------|-----------------------------------------|
| `atlases`        | Texture Atlas Sources                   |
| `blockstates`    | [Blockstate Files][bsfile]              |
| `equipment`      | [Equipment Info][equipment]             |
| `font`           | Font Definitions                        |
| `items`          | [Client Items][citems]                  |
| `lang`           | [Translation Files][translations]       |
| `models`         | [Models][models]                        |
| `particles`      | [Particle Definitions][particles]       |
| `post_effect`    | Post Processing Screen Effects          |
| `shaders`        | Metadata, Fragement, and Vertex Shaders |
| `sounds`         | [Sound Files][sounds]                   |
| `texts`          | Miscellaneous Text files                |
| `textures`       | [Textures][textures]                    |
| `waypoint_style` | Waypoint Icon Metadata                  |

## Data

_See also: [Data Packs][mcwikidatapacks] on the [Minecraft Wiki][mcwiki]_

In contrast to assets, data is the term for all [server][sides] resources. Similar to resource packs, data is loaded through data packs (or datapacks). Like a resource pack, a data pack consists of a [`pack.mcmeta` file][packmcmeta] and a root folder, named `data`. Then, again like with resource packs, that `data` folder contains subfolders for the various namespaces the resource pack includes; every namespace is one subfolder. For example, a data pack for a mod with the id `coolmod` will probably contain a `coolmod` namespace, but may additionally include other namespaces, such as `minecraft`.

NeoForge automatically applies all mod data packs to a new world upon creation. It is currently not possible to disable mod data packs. However, most data files can be overridden (and thus be removed by replacing them with an empty file) by a data pack with a higher priority. Additional data packs can be enabled or disabled by placing them in a world's `datapacks` subfolder and then enabling or disabling them through the [`/datapack`][datapackcmd] command.

:::info
There is currently no built-in way to apply a set of custom data packs to every world. However, there are a number of mods that achieve this.
:::

Data packs may contain folders with files affecting the following things:

| Folder Name                                                                                                               | Contents                     |
|---------------------------------------------------------------------------------------------------------------------------|------------------------------|
| `advancement`                                                                                                             | [Advancements][advancements] |
| `banner_pattern`                                                                                                          | Banner patterns              |
| `cat_variant`, `chicken_variant`, `cow_variant`, `frog_variant`, `pig_variant`, `wolf_variant`, `zombie_nautilus_variant` | Entity variants              |
| `cat_sound_variant`, `chicken_sound_variant`, `cow_sound_variant`, `pig_sound_variant`, `wolf_sound_variant`              | Entity sound variants        |
| `damage_type`                                                                                                             | [Damage types][damagetypes]  |
| `datapacks`                                                                                                               | Built-in datapacks           |
| `dialog`                                                                                                                  | Dialog menus                 |
| `enchantment`, `enchantment_provider`                                                                                     | [Enchantments][enchantment]  |
| `instrument`, `jukebox_song`                                                                                              | Sound reference metadata     |
| `painting_variant`                                                                                                        | Paintings                    |
| `loot_table`                                                                                                              | [Loot tables][loottables]    |
| `recipe`                                                                                                                  | [Recipes][recipes]           |
| `tags`                                                                                                                    | [Tags][tags]                 |
| `test_environment`, `test_instance`                                                                                       | [Game tests][gmt]            |
| `trade_set`, `villager_trade`                                                                                             | Villager trades              |
| `trial_spawner`                                                                                                           | Combat challenges            |
| `trim_material`, `trim_pattern`                                                                                           | Armor trims                  |
| `neoforge/data_maps`                                                                                                      | [Data maps][datamap]         |
| `neoforge/loot_modifiers`                                                                                                 | [Global loot modifiers][glm] |
| `dimension`, `dimension_type`, `structure`, `timeline`, `worldgen`, `neoforge/biome_modifier`                             | Worldgen files               |

Additionally, they may also contain subfolders for some systems that integrate with commands. These systems are rarely used in conjunction with mods, but worth mentioning regardless:

| Folder name     | Contents                       |
|-----------------|--------------------------------|
| `chat_type`     | [Chat types][chattype]         |
| `function`      | [Functions][function]          |
| `item_modifier` | [Item modifiers][itemmodifier] |
| `predicate`     | [Predicates][predicate]        |

## `pack.mcmeta`

_See also: [`pack.mcmeta` (Resource Pack)][packmcmetaresourcepack] and [`pack.mcmeta` (Data Pack)][packmcmetadatapack] on the [Minecraft Wiki][mcwiki]_

[`pack.mcmeta` files][meta] hold the metadata of a resource or data pack. For mods, NeoForge makes this file obsolete, as the `pack.mcmeta` is generated synthetically. In case you still need a `pack.mcmeta` file, the full specification can be found in the linked Minecraft Wiki articles.

## Data Generation

Data generation, colloquially known as datagen, is a way to programmatically generate JSON resource files, in order to avoid the tedious and error-prone process of writing them by hand. The name is a bit misleading, as it works for assets as well as data.

Datagen is run through the Data run configuration, which is generated for you alongside the Client and Server run configurations. The data run configuration follows the [mod lifecycle][lifecycle] until after the registry events are fired. It then fires one of the [`GatherDataEvent`s][event], in which you can register your to-be-generated objects in the form of data providers, writes said objects to disk, and ends the process.

There are two subtypes which operate on the [**physical side**][physicalside]: `GatherDataEvent.Client` and `GatherDataEvent.Server`.  `GatherDataEvent.Client` may contain all providers to generate. `GatherDataEvent.Server`, on the other hand, may only contain the providers used to generate datapack entries.

:::note
There are two recommendations on how to register your providers. The former is to register all of them in `GatherDataEvent.Client` and use the `runClientData` task to generate the data. The latter is to register client providers to `GatherDataEvent.Client` and server providers to `GatherDataEvent.Server`, generating them by running the `runClientData` and `runServerData` tasks, respectively.

As the MDK uses the former solution by setting up the default `clientData` configuration, all examples shown will use the former by registering all providers to `GatherDataEvent.Client`.
:::

All data providers extend the `DataProvider` interface and usually require one method to be overridden. The following is a list of noteworthy data generators Minecraft and NeoForge offer (the linked articles add further information, such as helper methods):

| Class                                                | Method                           | Generates                                                               | Side   | Notes                                                                                                           |
|------------------------------------------------------|----------------------------------|-------------------------------------------------------------------------|--------|-----------------------------------------------------------------------------------------------------------------|
| [`ModelProvider`][modelprovider]                     | `registerModels()`               | Models, Blockstate Files, Client Items                                                             | Client |                                                                                                                 |
| [`LanguageProvider`][langprovider]                   | `addTranslations()`              | Translations                                                            | Client | Also requires passing the language in the constructor.                                                          |
| [`EquipmentAssetProvider`][equipmentasset]           | `registerModels()`               | Assets for armor models                                                 | Client |                                                                                                                 |
| [`ParticleDescriptionProvider`][particleprovider]    | `addDescriptions()`              | Particle definitions                                                    | Client |                                                                                                                 |
| [`SoundDefinitionsProvider`][soundprovider]          | `registerSounds()`               | Sound definitions                                                       | Client |                                                                                                                 |
| `SpriteSourceProvider`                               | `gather()`                       | Sprite sources / atlases                                                | Client |                                                                                                                 |
| [`AdvancementProvider`][advancementprovider]         | `generate()`                     | Advancements                                                            | Server | Requires extra classes to work properly, see linked article for details.                                                   |
| [`LootTableProvider`][loottableprovider]             | `generate()`                     | Loot tables                                                             | Server | Requires extra methods and classes to work properly, see linked article for details.                            |
| [`RecipeProvider`][recipeprovider]                   | `buildRecipes(RecipeOutput)`     | Recipes                                                                 | Server | Requires extra classes to work properly, see linked article for details.                                                   |
| [`RecipePrioritiesProvider`][recipepriorities]       | `start()`                        | Priority order for recipes                                              | Server |                                                                                                                 |
| [Various subclasses of `TagsProvider`][tagsprovider] | `addTags(HolderLookup.Provider)` | Tags                                                                    | Server | Several specialized subclasses exist, see linked article for details.                                           |
| [`DataMapProvider`][datamapprovider]                 | `gather()`                       | Data map entries                                                        | Server |                                                                                                                 |
| [`GlobalLootModifierProvider`][glmprovider]          | `start()`                        | Global loot modifiers                                                   | Server |                                                                                                                 |
| [`DatapackBuiltinEntriesProvider`][datapackprovider] | N/A                              | Datapack builtin entries, e.g. worldgen and [damage types][damagetypes] | Server | No method overriding, instead entries are added in a lambda in the constructor. See linked article for details. |
| `JsonCodecProvider` (abstract class)                 | `gather()`                       | Objects with a codec                                                    | Both   | This can be extended for use with any object that has a [codec] to encode data to.                              |
| [`PackMetadataGenerator`][metagen]                   | `add(MetadataSectionType<T>, T)` | `pack.mcmeta`                                                           | Both |                                                                                                                 |

All of these providers follow the same pattern. First, you create a subclass and add your own resources to be generated. Then, you add the provider to the event in an [event handler][eventhandler]. An example using a `RecipeProvider`:

```java
public class MyRecipeProvider extends RecipeProvider {
    public MyRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    @Override
    protected void buildRecipes() {
        // Register your recipes here.
    }

    // The data provider class
    public static class Runner extends RecipeProvider.Runner {

        public Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new MyRecipeProvider(registries, output);
        }
    }
}

// In some event handler class
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    // Data providers should start by calling event.createDatapackRegistryObjects(...)
    // to register their datapack registry objects. This allows other providers
    // to use these objects during their own data generation.

    // From there, providers can generally be registered using event.createProvider(...),
    // which acts as a function that provides the PackOutput and optionally the
    // CompletableFuture<HolderLookup.Provider>.

    // Register the provider.
    event.createProvider(MyRecipeProvider.Runner::new);
    // Other data providers here.

    // If you want to create a datapack within the global pack, you can call
    // DataGenerator#getBuiltinDatapack. From there, you must use the
    // PackGenerator#addProvider method to add any providers to that pack.
    DataGenerator.PackGenerator examplePack = event.getGenerator().getBuiltinDatapack(
        true, // Should always be true.
        "examplemod", // The mod id.
        "example_pack" // The name of the pack.
    );
    
    examplePack.addProvider(output -> ...);
}
```

The event offers some helpers and context for you to use:

- `event.createDatapackRegistryObjects(...)` creates and registers a `DatapackBuiltinEntriesProvider` using the provided `RegistrySetBuilder`. It also forces any future use of the lookup provider to contain your datagenned entries.
- `event.createProvider(...)` registers a provider by providing the `PackOutput` and optionally the `CompletableFuture<HolderLookup.Provider>` as part of a lambda.
- `event.createBlockAndItemTags(...)` registers a `TagsProvider<Block>` and `TagsProvider<Item>` by constructing the `TagsProvider<Item>` using the `TagsProvider<Block>`.
- `event.getGenerator()` returns the `DataGenerator` that you register the providers to.
- `event.getPackOutput()` returns a `PackOutput` that is used by some providers to determine their file output location.
- `event.getResourceManager(PackType)` returns a `ResourceManager` that can be used by providers to check for already existing files.
- `event.getLookupProvider()` returns a `CompletableFuture<HolderLookup.Provider>` that is mainly used by tags and datagen registries to reference other, potentially not yet existing elements.
- `event.includeDev()` and `event.includeReports()` are `boolean` methods that allow you to check whether specific command line arguments (see below) are enabled.

### Command Line Arguments

The data generator can accept several command line arguments:

- `--mod examplemod`: Tells the data generator to run datagen for this mod. Automatically added by NeoGradle for the owning mod id, add this if you e.g. have multiple mods in one project.
- `--output path/to/folder`: Tells the data generator to output into the given folder. It is recommended to use Gradle's `file(...).getAbsolutePath()` to generate an absolute path for you (with a path relative to the project root directory). Defaults to `file('src/generated/resources').getAbsolutePath()`.
- `--existing path/to/folder`: Tells the data generator to consider the given folder when checking for existing files. Like with the output, it is recommended to use Gradle's `file(...).getAbsolutePath()`.
- `--existing-mod examplemod`: Tells the data generator to consider the resources in the given mod's JAR file when checking for existing files.
- Generator modes (all of these are boolean arguments and do not need any additional arguments):
    - `--includeDev`: Whether to run dev tools. Generally shouldn't be used by mods. Check at runtime with `GatherDataEvent#includeDev()`.
    - `--includeReports`: Whether to dump a list of registered objects. Check at runtime with `GatherDataEvent#includeReports()`.
    - `--all`: Enable all generator modes.

All arguments can be added to the run configurations by adding the following to your `build.gradle`:

```groovy
runs {
    // other run configurations here

    clientData {
        arguments.addAll '--arg1', 'value1', '--arg2', 'value2', '--all' // boolean args have no value
    }
}
```

For example, to replicate the default arguments, you could specify the following:

```groovy
runs {
    // other run configurations here

    clientData {
        arguments.addAll '--mod', 'examplemod', // insert your own mod id
                '--output', file('src/generated/resources').getAbsolutePath(),
                '--all'
    }
}
```

[advancementprovider]: server/advancements.md#data-generation
[advancements]: server/advancements.md
[bsfile]: client/models/index.md#blockstate-files
[chattype]: https://minecraft.wiki/w/Chat_type
[citems]: client/models/items.md
[codec]: ../datastorage/codecs.md
[damagetypes]: server/damagetypes.md
[datamap]: server/datamaps/index.md
[datamapprovider]: server/datamaps/index.md#data-generation
[datapackcmd]: https://minecraft.wiki/w/Commands/datapack
[datapackprovider]: ../concepts/registries.md#data-generation-for-datapack-registries
[enchantment]: server/enchantments/index.md
[equipment]: ../items/armor.md#equipment-models
[equipmentasset]: ../items/armor.md#equipment-assets
[event]: ../concepts/events.md
[eventhandler]: ../concepts/events.md#registering-an-event-handler
[function]: https://minecraft.wiki/w/Function_(Java_Edition)
[glm]: server/loottables/glm.md
[glmprovider]: server/loottables/glm.md#datagen
[gmt]: ../misc/gametest.md
[itemmodifier]: https://minecraft.wiki/w/Item_modifier
[langprovider]: client/i18n.md#datagen
[lifecycle]: ../concepts/events.md#the-mod-lifecycle
[logicalsides]: ../concepts/sides.md#the-logical-side
[loottableprovider]: server/loottables/index.md#datagen
[loottables]: server/loottables/index.md
[mcwiki]: https://minecraft.wiki
[mcwikidatapacks]: https://minecraft.wiki/w/Data_pack
[mcwikiresourcepacks]: https://minecraft.wiki/w/Resource_pack
[meta]: metadata.md
[metagen]: metadata.md#packmetadatagenerator
[modelprovider]: client/models/datagen.md
[models]: client/models/index.md
[packmcmeta]: #packmcmeta
[packmcmetadatapack]: https://minecraft.wiki/w/Data_pack#pack.mcmeta
[packmcmetaresourcepack]: https://minecraft.wiki/w/Resource_pack#Contents
[particleprovider]: client/particles.md#datagen
[particles]: client/particles.md
[physicalside]: ../concepts/sides.md#the-physical-side
[predicate]: https://minecraft.wiki/w/Predicate
[recipeprovider]: server/recipes/index.md#data-generation
[recipes]: server/recipes/index.md
[recipepriorities]: server/recipes/index.md#recipe-priorities
[sides]: ../concepts/sides.md
[soundprovider]: client/sounds.md#datagen
[sounds]: client/sounds.md
[tags]: server/tags.md
[tagsprovider]: server/tags.md#datagen
[textures]: client/textures.md
[translations]: client/i18n.md#language-files

## resources/server/advancements

# Advancements

Advancements are quest-like tasks that can be achieved by the player. Advancements are awarded based on advancement criteria, and can run behavior when completed.

A new advancement can be added by creating a JSON file in your namespace's `advancement` subfolder. So for example, if we want to add an advancement named `example_name` for a mod with the mod id `examplemod`, it will be located at `data/examplemod/advancement/example_name.json`. An advancement's ID will be relative to the `advancement` directory, so for our example, it would be `examplemod:example_name`. Any name can be chosen, and the advancement will automatically be picked up by the game. Java code is only necessary if you want to add new criteria or trigger a certain criterion from code (see below).

## Specification

An advancement JSON file may contain the following entries:

- `parent`: The parent advancement ID of this advancement. Circular references will be detected and cause a loading failure. Optional; if absent, this advancement will be considered a root advancement. Root advancements are advancements that have no parent set. They will be the root of their [advancement tree][tree].
- `display`: The object holding several properties used for display of the advancement in the advancement GUI. Optional; if absent, this advancement will be invisible, but can still be triggered.
    - `icon`: A [JSON representation of an item stack][itemstackjson].
    - `title`: A [text component][text] to use as the advancement's title.
    - `description`: A [text component][text] to use as the advancement's description.
    - `frame`: The frame type of the advancement. Accepts `challenge`, `goal` and `task`. Optional, defaults to `task`.
    - `background`: The texture to use for the tree background. This is relative to the `textures` directory, i.e. the `textures/` folder prefix should not be included. Optional, defaults to the missing texture. Only effective on root advancements.
    - `show_toast`: Whether to show a toast in the top right corner on completion. Optional, defaults to true.
    - `announce_to_chat`: Whether to announce advancement completion in the chat. Optional, defaults to true.
    - `hidden`: Whether to hide this advancement and all children from the advancement GUI until it is completed. Has no effect on root advancements themselves, but still hides all of their children. Optional, defaults to false.
- `criteria`: A map of criteria this advancement should track. Every criterion is identified by its map key. A list of criteria triggers added by Minecraft can be found in the `CriteriaTriggers` class, and the JSON specifications can be found on the [Minecraft Wiki][triggers]. For implementing your own criteria or triggering criteria from code, see below.
- `requirements`: A list of lists that determine what criteria are required. This is a list of OR lists that are ANDed together, or in other words, every sublist must have at least one criterion matching. Optional, defaults to all criteria being required.
- `rewards`: An object representing the rewards to grant when this advancement is completed. Optional, all values of the object are also optional.
    - `experience`: The amount of experience to award to the player.
    - `recipes`: A list of [recipe] IDs to unlock.
    - `loot`: A list of [loot tables][loottable] to roll and give to the player.
    - `function`: A [function] to run. If you want to run multiple functions, create a wrapper function that runs all other functions.
- `sends_telemetry_event`: Determines whether telemetry data should be collected when this advancement is completed or not. Only actually does anything if in the `minecraft` namespace. Optional, defaults to false.
- `neoforge:conditions`: NeoForge-added. A list of [conditions] that must be passed for the advancement to be loaded. Optional.

### Advancement Trees

Advancement files may be grouped in directories, which tells the game to create multiple advancement tabs. One advancement tab may contain one or more advancement trees, depending on the amount of root advancements. Empty advancement tabs will automatically be hidden.

:::tip
Minecraft only ever has one root advancement per tab, and always calls the root advancement `root`. It is suggested to follow this practice.
:::

## Criteria Triggers

To unlock an advancement, the specified criteria must be met. Criteria are tracked through triggers, which are executed from code when the associated action happens (e.g. the `player_killed_entity` trigger executes when the player kills the specified [entity]). Any time an advancement is loaded into the game, the criteria defined are read and added as listeners to the trigger. When a trigger is executed, all advancements that have a listener for the corresponding criterion are rechecked for completion. If the advancement is completed, the listeners are removed.

Custom criteria triggers are made up of two parts: the trigger, which is activated in code by calling `#trigger`, and the instance which defines the conditions under which the trigger should award the criterion. The trigger extends `SimpleCriterionTrigger<T>` while the instance implements `SimpleCriterionTrigger.SimpleInstance`. The generic value `T` represents the trigger instance type.

### `SimpleCriterionTrigger.SimpleInstance`

A `SimpleCriterionTrigger.SimpleInstance` represents a single criterion defined in the `criteria` object. Trigger instances are responsible for holding the defined conditions, and returning whether the inputs match the condition.

Conditions are usually passed in through the constructor. The `SimpleCriterionTrigger.SimpleInstance` interface requires only one function, called `#player`, which returns the conditions the player must meet as an `Optional<ContextAwarePredicate>`. If the subclass is a record with a `player` parameter of this type (as below), the automatically generated `#player` method will suffice.

```java
public record ExampleTriggerInstance(Optional<ContextAwarePredicate> player/*, other parameters here*/)
        implements SimpleCriterionTrigger.SimpleInstance {}
```

Typically, trigger instances have static helper methods which construct the full `Criterion<T>` object from the arguments to the instance. This allows these instances to be easily created during data generation, but are optional.

```java
// In this example, EXAMPLE_TRIGGER is a DeferredHolder<CriterionTrigger<?>, ExampleTrigger>.
// See below for how to register triggers.
public static Criterion<ExampleTriggerInstance> instance(ContextAwarePredicate player, ItemPredicate item) {
    return EXAMPLE_TRIGGER.get().createCriterion(new ExampleTriggerInstance(Optional.of(player), item));
}
```

Finally, a method should be added which takes in the current data state and returns whether the user has met the necessary conditions. The conditions of the player are already checked through `SimpleCriterionTrigger#trigger(ServerPlayer, Predicate)`. Most trigger instances call this method `#matches`.

```java
// Let's assume we have an additional ItemPredicate parameter. This can be whatever you need.
// For example, this could also be a Predicate<LivingEntity>.
public record ExampleTriggerInstance(Optional<ContextAwarePredicate> player, ItemPredicate predicate)
        implements SimpleCriterionTrigger.SimpleInstance {
    // This method is unique for each instance and is as such not overridden.
    // The parameter may be whatever you need to properly match, for example, this could also be a LivingEntity.
    // If you need no context other than the player, this may also take no parameters at all.
    public boolean matches(ItemStack stack) {
        // Since ItemPredicate matches a stack, we use a stack as the input here.
        return this.predicate.test(stack);
    }
}
```

### `SimpleCriterionTrigger`

The `SimpleCriterionTrigger<T>` implementation has two purposes: supplying a method to check trigger instances and run attached listeners on success, and specifying a [codec] to serialize the trigger instance (`T`).

First, we want to add a method that takes the inputs we need and calls `SimpleCriterionTrigger#trigger` to properly handle checking all listeners. Most trigger instances also name this method `#trigger`. Reusing our example trigger instance from above, our trigger would look something like this:

```java
public class ExampleCriterionTrigger extends SimpleCriterionTrigger<ExampleTriggerInstance> {
    // This method is unique for each trigger and is as such not a method to override
    public void trigger(ServerPlayer player, ItemStack stack) {
        this.trigger(player,
                // The condition checker method within the SimpleCriterionTrigger.SimpleInstance subclass
                triggerInstance -> triggerInstance.matches(stack)
        );
    }
}
```

Triggers must be registered to the `Registries.TRIGGER_TYPE` [registry][registration]:

```java
public static final DeferredRegister<CriterionTrigger<?>> TRIGGER_TYPES =
        DeferredRegister.create(Registries.TRIGGER_TYPE, ExampleMod.MOD_ID);

public static final Supplier<ExampleCriterionTrigger> EXAMPLE_TRIGGER =
        TRIGGER_TYPES.register("example", ExampleCriterionTrigger::new);
```

And then, triggers must define a [codec] to serialize and deserialize the trigger instance by overriding `#codec`. This codec is typically created as a constant within the instance implementation.

```java
public record ExampleTriggerInstance(Optional<ContextAwarePredicate> player/*, other parameters here*/)
        implements SimpleCriterionTrigger.SimpleInstance {
    public static final Codec<ExampleTriggerInstance> CODEC = ...;

    // ...
}

public class ExampleTrigger extends SimpleCriterionTrigger<ExampleTriggerInstance> {
    @Override
    public Codec<ExampleTriggerInstance> codec() {
        return ExampleTriggerInstance.CODEC;
    }

    // ...
}
```

For the earlier example of a record with a `ContextAwarePredicate` and an `ItemPredicate`, the codec could be:

```java
public static final Codec<ExampleTriggerInstace> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(ExampleTriggerInstance::player),
        ItemPredicate.CODEC.fieldOf("item").forGetter(ExampleTriggerInstance::item)
).apply(instance, ExampleTriggerInstance::new));
```

### Calling Criterion Triggers

Whenever the action being checked is performed, the `#trigger` method defined by our `SimpleCriterionTrigger` subclass should be called. Of course, you can also call on vanilla triggers, which are found in `CriteriaTriggers`.

```java
// In some piece of code where the action is being performed
// Again, EXAMPLE_TRIGGER is a supplier for the registered instance of the custom criterion trigger
public void performExampleAction(ServerPlayer player, additionalContextParametersHere) {
    // Run code to perform action here
    EXAMPLE_TRIGGER.get().trigger(player, additionalContextParametersHere);
}
```

## Data Generation

Advancements can be [datagenned][datagen] using an `AdvancementProvider`. An `AdvancementProvider` accepts a list of `AdvancementSubProviders`s, which actually generate the advancements using `Advancement.Builder`.

To start, create an instance of `AdvancementProvider` within one of the `GatherDataEvent`s:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createProvider((output, lookupProvider) -> new AdvancementProvider(
        output, lookupProvider,
        // Add generators here
        List.of(...)
    ));

     // Other providers
}
```

Now, the next step is to fill the list with our generators. To do so, we can either add generators as classes or lambdas, and then add an instance of each of them to the currently empty list in the constructor parameter.

```java
// Class example
public class MyAdvancementGenerator implements AdvancementSubProvider {

    @Override
    public void generate(HolderLookup.Provider registries, Consumer<AdvancementHolder> saver) {
        // Generate your advancements here.
    }
}

// Method Example
public class ExampleClass {

    // Matches the parameters provided by AdvancementSubProvider#generate
    public static void generateExampleAdvancements(HolderLookup.Provider registries, Consumer<AdvancementHolder> saver) {
        // Generate your advancements here.
    }
}

// In one of the `GatherDataEvent`s
event.createProvider((output, lookupProvider) -> new AdvancementProvider(
    output, lookupProvider,
    // Add generators here
    List.of(
        // Add an instance of our generator to the list parameter. This can be done as many times as you want.
        // Having multiple generators is purely for organization, all functionality can be achieved with a single generator.
        new MyAdvancementGenerator(),
        ExampleClass::generateExampleAdvancements
    )
));
```

To generate an advancement, you want to use an `Advancement.Builder`:

```java
// All methods follow the builder pattern, meaning that chaining is possible and encouraged.
// For better readability of the explanations, chaining will not be done here.

// Create an advancement builder using the static #advancement() method.
// Using #advancement() automatically enables telemetry events. If you do not want this,
// #recipeAdvancement() can be used instead, there are no other functional differences.
Advancement.Builder builder = Advancement.Builder.advancement();

// Sets the parent of the advancement. You can use another advancement you have already generated,
// or create a placeholder advancement using the static AdvancementSubProvider#createPlaceholder method.
builder.parent(AdvancementSubProvider.createPlaceholder("minecraft:story/root"));

// Sets the display properties of the advancement. This can either be a DisplayInfo object,
// or pass in the values directly. If values are passed in directly, a DisplayInfo object will be created for you.
builder.display(
        // The advancement icon. Can be an ItemStackTemplate or an ItemLike.
        new ItemStackTemplate(Items.GRASS_BLOCK),
        // The advancement title and description. Don't forget to add translations for these!
        Component.translatable("advancements.examplemod.example_advancement.title"),
        Component.translatable("advancements.examplemod.example_advancement.description"),
        // The background texture. Use null if you don't want a background texture (for non-root advancements).
        null,
        // The frame type. Valid values are AdvancementType.TASK, CHALLENGE, or GOAL.
        AdvancementType.GOAL,
        // Whether to show the advancement toast or not.
        true,
        // Whether to announce the advancement into chat or not.
        true,
        // Whether the advancement should be hidden or not.
        false
);

// An advancement reward builder. Can be created with any of the four reward types, and further rewards
// can be added using the methods prefixed with add. This can also be built beforehand,
// and the resulting AdvancementRewards can then be reused across multiple advancement builders.
builder.rewards(
    // Alternatively, use addExperience() to add to an existing builder.
    AdvancementRewards.Builder.experience(100)
    // Alternatively, use loot() to create a new builder.
    .addLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("minecraft", "chests/igloo")))
    // Alternatively, use recipe() to create a new builder.
    .addRecipe(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("minecraft", "iron_ingot")))
    // Alternatively, use function() to create a new builder.
    .runs(Identifier.fromNamespaceAndPath("examplemod", "example_function"))
);

// Adds a criterion with the given name to the advancement. Use the corresponding trigger instance's static method.
builder.addCriterion("pickup_dirt", InventoryChangeTrigger.TriggerInstance.hasItems(Items.DIRT));

// Adds a requirements handler. Minecraft natively provides allOf() and anyOf(), more complex requirements
// must be implemented manually. Only has an effect with two or more criteria.
builder.requirements(AdvancementRequirements.allOf(List.of("pickup_dirt")));

// Save the advancement to disk, using the given resource location. This returns an AdvancementHolder,
// which may be stored in a variable and used as a parent by other advancement builders.
builder.save(saver, Identifier.fromNamespaceAndPath("examplemod", "example_advancement"));
```

[codec]: ../../datastorage/codecs.md
[conditions]: conditions.md
[datagen]: ../index.md#data-generation
[entity]: ../../entities/index.md
[function]: https://minecraft.wiki/w/Function_(Java_Edition)
[itemstackjson]: ../../items/index.md#json-representation
[loottable]: loottables/index.md
[recipe]: recipes/index.md
[registration]: ../../concepts/registries.md#methods-for-registering
[root]: #root-advancements
[text]: ../client/i18n.md#components
[tree]: #advancement-trees
[triggers]: https://minecraft.wiki/w/Advancement/JSON_format#List_of_triggers

## resources/server/conditions

# Data Load Conditions

Sometimes, it is desirable to disable or enable certain features if another mod is present, or if any mod adds another type of ore, etc. For these use cases, NeoForge adds data load conditions. These were originally called recipe conditions, since recipes were the original use case for this system, but it has since been extended to other systems. This is also why some of the built-in conditions are limited to items.

Most JSON files can optionally declare a `neoforge:conditions` block in the root, which will be evaluated before the data file is actually loaded. Loading will continue if and only if all conditions pass, otherwise the data file will be ignored. (The exception to this rule are [loot tables][loottable], which will be replaced with an empty loot table instead.)

```json5
{
    "neoforge:conditions": [
        {
            // Condition 1
        },
        {
            // Condition 2
        },
        // ...
    ],
    // The rest of the data file
}
```

:::note
If the value to load is not a map/object, it is stored within `neoforge:value`:

```json5
{
    "neoforge:conditions": [ /* ...*/ ],
    "neoforge:value": 2 // The value to load
}
```
:::

For example, if we want to only load our file if a mod with id `examplemod` is present, our file would look something like this:

```json5
{
    // highlight-start
    "neoforge:conditions": [
        {
            "type": "neoforge:mod_loaded",
            "modid": "examplemod"
        }
    ],
    // highlight-end
    "type": "minecraft:crafting_shaped",
    // ...
}
```

:::note
Most vanilla files have been patched to use conditions using the `ConditionalCodec` wrapper. However, not all systems, especially those not using a [codec], can use conditions. To find out whether a data file can use conditions, check the backing codec definition. 
:::

## Built-In Conditions

### `neoforge:always` and `neoforge:never`

These consist of no data and return the expected value.

```json5
{
    // Will always return true (or false for "neoforge:never")
    "type": "neoforge:always"
}
```

:::tip
Using the `neoforge:never` condition very cleanly allows disabling any data file. Simply place a file with the following contents at the needed location:

```json5
{"neoforge:conditions":[{"type":"neoforge:never"}]}
```

Disabling files this way will **not** cause log spam.
:::

### `neoforge:not`

This condition accepts another condition and inverts it.

```json5
{
    // Inverts the result of the stored condition
    "type": "neoforge:not",
    "value": {
        // Another condition
    }
}
```

### `neoforge:and` and `neoforge:or`

These conditions accept the condition(s) being operated upon and apply the expected logic. There is no limit to the amount of accepted conditions.

```json5
{
    // ANDs the stored conditions together (or ORs for "neoforge:or")
    "type": "neoforge:and",
    "values": [
        {
            // First condition
        },
        {
            // Second condition
        }
    ]
}
```

### `neoforge:mod_loaded`

This condition returns true if a mod with the given mod id is loaded, and false otherwise.

```json5
{
    "type": "neoforge:mod_loaded",
    // Returns true if "examplemod" is loaded
    "modid": "examplemod"
}
```

### `neoforge:registered`

This condition returns true if an object in a specific registry with the given registry name has been registered, and false otherwise.

```json5
{
    "type": "neoforge:registered",
    // The registry to check the value for
    // Defaults to `minecraft:item`
    "registry": "minecraft:item",
    // Returns true if "examplemod:example_item" has been registered
    "value": "examplemod:example_item"
}
```

### `neoforge:tag_empty`

This condition returns true if the given registry [tag] is empty, and false otherwise.

```json5
{
    "type": "neoforge:tag_empty",
    // The registry to check the tag for
    // Defaults to `minecraft:item`
    "registry": "minecraft:item",
    // Returns true if "examplemod:example_tag" is an empty tag
    "tag": "examplemod:example_tag"
}
```

### `neoforge:feature_flags_enabled`

This condition returns true if the provided [feature flags][flags] are enabled, and false otherwise.

```json5
{
    "type": "neoforge:feature_flags_enabled",
    // Returns true if the "examplemod:example_feature" is enabled
    "flags": [
        "examplemod:example_feature"
    ]
}
```

## Creating Custom Conditions

Custom conditions can be created by implementing `ICondition` and its `#test(IContext)` method, as well as creating a [map codec][codec] for it. The `IContext` parameter in `#test` has access to some parts of the game state. Currently, this only allows you to query tags from registries. Some objects with conditions may be loaded earlier than tags, in which case the context will be `IContext.EMPTY` and not contain any tag information at all.

For example, let's assume we want to implement an `xor` condition, then our condition would look something like this:

```java
public record XorCondition(ICondition first, ICondition second) implements ICondition {
    public static final MapCodec<XorCondition> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            ICondition.CODEC.fieldOf("first").forGetter(XorCondition::first),
            ICondition.CODEC.fieldOf("second").forGetter(XorCondition::second)
    ).apply(inst, XorCondition::new));

    @Override
    public boolean test(ICondition.IContext context) {
        return this.first.test(context) ^ this.second.test(context);
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
```

Conditions are a registry of codecs. As such, we need to [register] our codec, like so:

```java
public static final DeferredRegister<MapCodec<? extends ICondition>> CONDITION_CODECS =
        DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, ExampleMod.MOD_ID);

public static final Supplier<MapCodec<XorCondition>> XOR =
        CONDITION_CODECS.register("xor", () -> XorCondition.CODEC);
```

And then, we can use our condition in some data file (assuming we registered the condition under the `examplemod` namespace):

```json5
{
    "neoforge:conditions": [
        {
            "type": "examplemod:xor",
            "first": {
                // Either this condition is true
                "type": "..."
            },
            "second": {
                // Or this condition, not both!
                "type": "..."
            }
        }
    ],
    // The rest of the data file
}
```

## Datagen

While any datapack JSON file can use load conditions, only a few [data providers][datagen] have been modified to be able to generate them. These include:

- [`RecipeProvider`][recipeprovider] (via `RecipeOutput#withConditions`), including recipe advancements
- `JsonCodecProvider` and its subclass `SpriteSourceProvider`
- [`DataMapProvider`][datamapprovider]
- [`GlobalLootModifierProvider`][glmprovider]
- [`DatapackBuiltinEntriesProvider`][datapackentries] (via `Map<ResourceKey<?>, List<ICondition>>` parameter)

For the conditions themselves, the `NeoForgeConditions` class provides static helpers for each of the built-in condition types that return the corresponding `ICondition`s.

[codec]: ../../datastorage/codecs
[datagen]: ../index.md#data-generation
[datamapprovider]: datamaps/index.md#data-generation
[datapackentries]: ../../concepts/registries.md#data-generation-for-datapack-registries
[flags]: ../../advanced/featureflags.md
[glmprovider]: loottables/glm.md#datagen
[loottable]: loottables/index.md
[recipeprovider]: recipes/index.md#data-generation
[register]: ../../concepts/registries
[tag]: tags.md

## resources/server/damagetypes

# Damage Types & Damage Sources

A damage type denotes what kind of damage is being applied to an [entity] - physical damage, fire damage, drowning damage, magic damage, void damage, etc. The distinction into damage types is used for various immunities (e.g. blazes won't take fire damage), enchantments (e.g. blast protection will only protect against explosion damage), and many more use cases.

A damage type is a template for a damage source, so to speak. Or in other words, a damage source can be viewed as a damage type instance. Damage types exist as [`ResourceKey`s][rk] in code, but have all of their properties defined in data packs. Damage sources, on the other hand, are created as needed by the game, based off the values in the data pack files. They can hold additional context, for example the attacking entity.

## Creating Damage Types

To get started, you want to create your own `DamageType`. `DamageType`s are a [datapack registry][dr], and as such, new `DamageType`s are not registered in code, but are registered automatically when the corresponding files are added. However, we still need to provide some point for the code to get the damage sources from. We do so by specifying a [resource key][rk]:

```java
public static final ResourceKey<DamageType> EXAMPLE_DAMAGE =
        ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(ExampleMod.MOD_ID, "example"));
```

Now that we can reference it from code, let's specify some properties in the data file. Our data file is located at `data/examplemod/damage_type/example.json` (swap out `examplemod` and `example` for the mod id and the name of the resource location) and contains the following:

```json5
{
    // The death message id of the damage type. The full death message translation key will be
    // "death.attack.examplemod.example" (with swapped-out mod id and name).
    "message_id": "examplemod.example",
    // Whether this damage type's damage amount scales with difficulty or not. Valid vanilla values are:
    // - "never": The damage value remains the same on any difficulty. Common for player-caused damage types.
    // - "when_caused_by_living_non_player": The damage value is scaled if the entity is caused by a
    //   living entity of some sort, including indirectly (e.g. an arrow shot by a skeleton), that is not a player.
    // - "always": The damage value is always scaled. Commonly used by explosion-like damage.
    "scaling": "when_caused_by_living_non_player",
    // The amount of exhaustion caused by receiving this kind of damage.
    "exhaustion": 0.1,
    // The damage effects (currently only sound effects) that are applied when receiving this kind of damage. Optional.
    // Valid vanilla values are "hurt" (default), "thorns", "drowning", "burning", "poking", and "freezing".
    "effects": "hurt",
    // The death message type. Determines how the death message is built. Optional.
    // Valid vanilla values are "default" (default), "fall_variants", and "intentional_game_design".
    "death_message_type": "default"
}
```

:::tip
The `scaling`, `effects` and `death_message_type` fields are internally controlled by the enums `DamageScaling`, `DamageEffects` and `DeathMessageType`, respectively. These enums can be [extended][extenum] to add custom values if needed.
:::

The same format is also used for vanilla's damage types, and pack developers can change these values if needed.
 
## Creating and Using Damage Sources

`DamageSource`s are usually created on the fly when [`Entity#hurt`][entityhurt] is called. Be aware that since damage types are a [datapack registry][dr], you will need a `RegistryAccess` to query them, which can be obtained via `Level#registryAccess`. To create a `DamageSource`, call the `DamageSource` constructor with up to four parameters:

```java
DamageSource damageSource = new DamageSource(
        // The damage type holder to use. Query from the registry. This is the only required parameter.
        registryAccess.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(EXAMPLE_DAMAGE),
        // The direct entity. For example, if a skeleton shot you, the skeleton would be the causing entity
        // (= the parameter above), and the arrow would be the direct entity (= this parameter). Similar to
        // the causing entity, this isn't always applicable and therefore nullable. Optional, defaults to null.
        null,
        // The entity causing the damage. This isn't always applicable (e.g. when falling out of the world)
        // and may therefore be null. Optional, defaults to null.
        null,
        // The damage source position. This is rarely used, one example would be intentional game design
        // (= nether beds exploding). Nullable and optional, defaulting to null.
        null
);
```

:::warning
`DamageSources#source`, which is a wrapper around `new DamageSource`, flips the second and third parameters (direct entity and causing entity). Make sure you are supplying the correct values to the correct parameters.
:::

If `DamageSource`s have no entity or position context whatsoever, it makes sense to cache them in a field. For `DamageSource`s that do have entity or position context, it is common to add helper methods, like so:

```java
public static DamageSource exampleDamage(Entity causer) {
    return new DamageSource(
            causer.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(EXAMPLE_DAMAGE),
            causer);
}
```

:::tip
Vanilla's `DamageSource` factories can be found in `DamageSources`, and vanilla's `DamageType` resource keys can be found in `DamageTypes`. Entities also have the method `Entity#damageSources`, which is a convenience getter for the `DamageSources` instance.
:::

The first and foremost use case for damage sources is `Entity#hurt`. This method is called whenever an entity is receiving damage. To hurt an entity with our own damage type, we simply call `Entity#hurt` ourselves:

```java
// The second parameter is the amount of damage, in half hearts.
entity.hurt(exampleDamage(player), 10);
```

Other damage type-specific behavior, such as invulnerability checks, is often run through damage type [tags]. These are both added by Minecraft and NeoForge and can be found under `DamageTypeTags` and `Tags.DamageTypes`, respectively.

## Datagen

_For more info, see [Data Generation for Datapack Registries][drdatagen]._

Damage type JSON files can be [datagenned][datagen]. Since damage types are a datapack registry, we add a `DatapackBuiltinEntriesProvider` via `GatherDataEvent#createDatapackRegistryObjects` and put our damage types in the `RegistrySetBuilder`:

```java
// In your datagen class
@SubscribeEvent // on the mod event bus
public static void onGatherData(GatherDataEvent.Client event) {
    event.createDatapackRegistryObjects(new RegistrySetBuilder()
        // Add a datapack builtin entry provider for damage types. If this lambda becomes longer,
        // this should probably be extracted into a separate method for the sake of readability.
        .add(Registries.DAMAGE_TYPE, bootstrap -> {
            // Use new DamageType() to create an in-code representation of a damage type.
            // The parameters map to the values of the JSON file, in the order seen above.
            // All parameters except for the message id and the exhaustion value are optional.
            bootstrap.register(EXAMPLE_DAMAGE, new DamageType(EXAMPLE_DAMAGE.identifier(),
                DamageScaling.WHEN_CAUSED_BY_LIVING_NON_PLAYER,
                0.1f,
                DamageEffects.HURT,
                DeathMessageType.DEFAULT)
            )
        })
        // Add datapack providers for other datapack entries, if applicable.
        .add(...)
    );

    // ...
}
```

[datagen]: ../index.md#data-generation
[dr]: ../../concepts/registries.md#datapack-registries
[drdatagen]: ../../concepts/registries.md#data-generation-for-datapack-registries
[entity]: ../../entities/index.md
[entityhurt]: ../../entities/index.md#damaging-entities
[extenum]: ../../advanced/extensibleenums.md
[rk]: ../../misc/identifier.md#resourcekeys
[tags]: tags.md

## resources/server/datamaps/builtin

# Built-In Data Maps

NeoForge provides various built-in [data maps][datamap] for common use cases, replacing hardcoded vanilla fields. Vanilla values are shipped by data map files in NeoForge, so there is no functional difference to the player.

## `neoforge:acceptable_villager_distances`

Allows configuring the maximum block distance that villagers will notice an entity, as a replacement for `VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES` (which will be ignored in 26.2). This data map is located at `neoforge/data_maps/entity_type/acceptable_villager_distances.json` and its objects have the following structure:

```json5
{
    // The maximum block distance that a villager will detect this entity as hostile
    "acceptable_villager_distance": 4.0
}
```

Example:

```json5
{
    "values": {
        // Villagers will detect a blaze as hostile if it is within 4 blocks of its position
        "minecraft:blaze": {
            "acceptable_villager_distance": 4.0
        }
    }
}
```

## `neoforge:compostables`

Allows configuring composter values, as a replacement for `ComposterBlock.COMPOSTABLES` (which is now ignored). This data map is located at `neoforge/data_maps/item/compostables.json` and its objects have the following structure:

```json5
{
    // A 0 to 1 (inclusive) float representing the chance that the item will update the level of the composter
    "chance": 1,
    // Optional, defaults to false - whether farmer villagers can compost this item
    "can_villager_compost": false
}
```

Example:

```json5
{
    "values": {
        // Give acacia logs a 50% chance that they will fill a composter
        "minecraft:acacia_log": {
            "chance": 0.5
        }
    }
}
```

## `neoforge:furnace_fuels`

Allows configuring item burn times. This data map is located at `neoforge/data_maps/item/furnace_fuels.json` and its objects have the following structure:

```json5
{
    // A positive integer representing the item's burn time in ticks
    "burn_time": 1000
}
```

Example:

```json5
{
    "values": {
        // Give anvils a 2 seconds burn time
        "minecraft:anvil": {
            "burn_time": 40
        }
    }
}
```

:::info
NeoForge additionally adds the `IItemExtension#getBurnTime` method to be overridden in custom items, overruling this data map. `#getBurnTime` should only be used in scenarios where the datamap does not suffice, for example [data component][datacomponent]-dependent burn times.
:::

:::warning
Vanilla adds an implicit burn time of 300 ticks (15 seconds) for `#minecraft:logs` and `#minecraft:planks`, and then hardcodes the removal of crimson and warped items from that. This means that if you add another non-flammable wood, you should add a removal for that wood type's items from this map, like so:

```json5
{
    "replace": false,
    "values": [
        // values here
    ],
    "remove": [
        "examplemod:example_nether_wood_planks",
        "#examplemod:example_nether_wood_stems",
        "examplemod:example_nether_wood_door",
        // etc.
        // other removals here
    ]
}
```
:::

## `neoforge:monster_room_mobs`

Allows configuring the mobs that may appear in the mob spawner in a monster room, as a replacement for `MonsterRoomFeature#MOBS` (which is now ignored). This data map is located at `neoforge/data_maps/entity_type/monster_room_mobs.json` and its objects have the following structure:

```json5
{
    // The weight of this mob, relative to other mobs in the datamap
    "weight": 100
}
```

Example:

```json5
{
    "values": {
        // Make squids appear in monster room spawners with a weight of 100
        "minecraft:squid": {
            "weight": 100
        }
    }
}
```

## `neoforge:oxidizables`

Allows configuring oxidation stages, as a replacement for `WeatheringCopper#NEXT_BY_BLOCK`. This data map is also used to build a reverse deoxidation map (for scraping with an axe). It is located at `neoforge/data_maps/block/oxidizables.json` and its objects have the following structure:

```json5
{
    // The block this block will turn into once oxidized
    "next_oxidation_stage": "examplemod:oxidized_block"
}
```

:::note
Custom blocks must implement `WeatheringCopperFullBlock` or `WeatheringCopper` and call `changeOverTime` in `randomTick` to oxidize naturally.
:::

Example:

```json5
{
    "values": {
        "mymod:custom_copper": {
            // Make a custom copper block oxidize into custom oxidized copper
            "next_oxidation_stage": "mymod:custom_oxidized_copper"
        }
    }
}
```

## `neoforge:parrot_imitations`

Allows configuring the sounds produced by parrots when they want to imitate a mob, as a replacement for `Parrot#MOB_SOUND_MAP` (which is now ignored). This data map is located at `neoforge/data_maps/entity_type/parrot_imitations.json` and its objects have the following structure:

```json5
{
    // The ID of the sound that parrots will produce when imitating the mob
    "sound": "minecraft:entity.parrot.imitate.creeper"
}
```

Example:

```json5
{
    "values": {
        // Make parrots produce the ambient cave sound when imitating allays
        "minecraft:allay": {
            "sound": "minecraft:ambient.cave"
        }
    }
}
```

## `neoforge:raid_hero_gifts`

Allows configuring the gift that a villager with a certain `VillagerProfession` may gift you if you stop the raid, as a replacement for `GiveGiftToHero#GIFTS` (which is now ignored). This data map is located at `neoforge/data_maps/villager_profession/raid_hero_gifts.json` and its objects have the following structure:

```json5
{
    // The ID of the loot table that a villager profession will hand out after a raid
    "loot_table": "minecraft:gameplay/hero_of_the_village/armorer_gift"
}
```

Example:

```json5
{
    "values": {
        "minecraft:armorer": {
            // Make armorers give the raid hero the armorer gift loot table
            "loot_table": "minecraft:gameplay/hero_of_the_village/armorer_gift"
        }
    }
}
```

## `neoforge:strippables`

Allows configuring the block a block will turn into when stripped (right clicked with an axe, or an item with the item ability `ItemAbilities#AXE_STRIP`), as a replacement for `AxeItem#STRIPPABLES` (which will be ignored in 26.2). This data map is located at `neoforge/data_maps/block/strippables.json` and its objects have the following structure:

```json5
{
    // The block this block will turn into when stripped by tool with the item ability `ItemAbilities#AXE_STRIP`
    "stripped_block": "examplemod:stripped_wood"
}
```

Example:

```json5
{
    "values": {
        "examplemod:wood": {
            // Make a custom wood block strip into a custom stripped wood block
            "stripped_block": "examplemod:stripped_wood"
        }
    }
}
```

## `neoforge:vibration_frequencies`

Allows configuring the sculk vibration frequencies emitted by game events, as a replacement for `VibrationSystem#VIBRATION_FREQUENCY_FOR_EVENT` (which is now ignored). This data map is located at `neoforge/data_maps/game_event/vibration_frequencies.json` and its objects have the following structure:

```json5
{
    // An integer between 1 and 15 (inclusive) that indicates the vibration frequency of the event
    "frequency": 2
}
```

Example:

```json5
{
    "values": {
        // Make the splash in water game event vibrate on the second frequency
        "minecraft:splash": {
            "frequency": 2
        }
    }
}
```

## `neoforge:villager_types`

Allows configuring the villager type that will spawn based on its biome, as a replacement for `VillagerType#BY_BIOME` (which will be ignored in 26.2). It is located at `neoforge/data_maps/worldgen/biome/villager_types.json` and its objects have the following structure:

```json5
{
    // The villager type that will spawn in this biome
    // If no villager type is specified for a biome, then `minecraft:plains` will be used
    "villager_type": "minecraft:desert"
    
}
```

Example:

```json5
{
    "values": {
        // Make villagers in the jungle biome be of the desert type
        "minecraft:jungle": {
            "villager_type": "minecraft:desert"
        }
    }
}
```

## `neoforge:waxables`

Allows configuring the block a block will turn into when waxed (right clicked with a honeycomb), as a replacement for `HoneycombItem#WAXABLES`. This data map is also used to build a reverse dewaxing map (for scraping with an axe). It is located at `neoforge/data_maps/block/waxables.json` and its objects have the following structure:

```json5
{
    // The waxed variant of this block
    "waxed": "minecraft:iron_block"
}
```

Example:

```json5
{
    "values": {
        // Make gold blocks turn into iron blocks once waxed
        "minecraft:gold_block": {
            "waxed": "minecraft:iron_block"
        }
    }
}
```

[datacomponent]: ../../../items/datacomponents.md
[datamap]: index.md

## resources/server/datamaps/index

# Data Maps

A data map contains data-driven, reloadable objects that can be attached to a registered object. This system allows for more easily data-driving game behavior, as they provide functionality such as syncing or conflict resolution, leading to a better and more configurable user experience. You can think of [tags] as registry object ➜ boolean maps, while data maps are more flexible registry object ➜ object maps. Similar to [tags], data maps will add to their corresponding data map rather than overwriting.

Data maps can be attached to both static, built-in, registries and dynamic data-driven datapack registries. Data maps support reloading through the use of the `/reload` command or any other means that reload server resources.

NeoForge provides various [built-in data maps][builtin] for common use cases, replacing hardcoded vanilla fields. More info can be found in the linked article.

## File Location

Data maps are loaded from a JSON file located at `<mapNamespace>/data_maps/<registryNamespace>/<registryPath>/<mapPath>.json`, where:

- `<mapNamespace>` is the namespace of the ID of the data map,
- `<mapPath>` is the path of the ID of the data map,
- `<registryNamespace>` is the namespace of the ID of the registry (omitted if it is `minecraft`), and
- `<registryPath>` is the path of the ID of the registry.

Examples:

- For a data map named `mymod:drop_healing` for the `minecraft:item` registry (as in the example below), the path will be `mymod/data_maps/item/drop_healing.json`.
- For a data map named `somemod:somemap` for the `minecraft:block` registry, the path will be `somemod/data_maps/block/somemap.json`.
- For a data map named `example:stuff` for the `somemod:custom` registry, the path will be `example/data_maps/somemod/custom/stuff.json`.

## JSON Structure

A data map file itself may contain the following fields:

- `replace`: A boolean that will clear the data map before adding the values of this file. This should never be shipped by mods, and only be used by pack developers that want to overwrite this map for their own purposes.
- `neoforge:conditions`: A list of [loading conditions][conditions].
- `values`: A map of registry IDs or tag IDs to values that should be added to the data map by your mod. The structure of the values themselves is defined by the data map's codec (see below).
- `remove`: A list of registry IDs or tag IDs to be removed from the data map.

### Adding Values

For example, let's assume that we have a data map object with two float keys `amount` and `chance` for the registry `minecraft:item`. A corresponding data map file could look something like this:

```json5
{
    "values": {
        // Attach a value to the carrot item
        "minecraft:carrot": {
            "amount": 12,
            "chance": 1
        },
        // Attach a value to all items in the logs tag
        "#minecraft:logs": {
            "amount": 1,
            "chance": 0.1
        }
    }
}
```

Data maps may support [mergers][mergers], which will cause custom merging behavior in the case of a conflict, e.g. if two mods add a data map value for the same item. To avoid the merger from triggering, we can specify the `replace` field on the element level, like so:

```json5
{
    "values": {
        // Overwrite the value of the carrot item
        "minecraft:carrot": {
            // highlight-next-line
            "replace": true,
            // The new value will be under a value sub-object
            "value": {
                "amount": 12,
                "chance": 1
            }
        }
    }
}
```

### Removing Existing Values

Removing elements can be done by specifying a list of item IDs or tag IDs to remove:

```json5
{
    // We do not want the potato to have a value, even if another mod's data map added it
    "remove": [
        "minecraft:potato"
    ]
}
```

Removals run after additions, so we can include a tag and then exclude certain elements from it again:

```json5
{
    "values": {
        "#minecraft:logs": { /* ... */ }
    },
    // Exclude crimson stem again
    "remove": [
        "minecraft:crimson_stem"
    ]
}
```

Data maps may support custom [removers] with additional arguments. To supply these, the `remove` list can be transformed into a JSON object that contains the to-be-removed elements as map keys and the additional data as the associated value. For example, let's assume that our remover object is serialized to a string, then our remover map could look something like this:

```json5
{
    "remove": {
        // The remover will be deserialized from the value (`somekey1` in this case)
        // and applied to the value attached to the carrot item
        "minecraft:carrot": "somekey1"
    }
}
```

## Custom Data Maps

To begin, we define the format of our data map entries. **Data map entries must be immutable**, making records ideal for this. Reiterating our example from above with two float values `amount` and `chance`, our data map entries will look something like this:

```java
public record ExampleData(float amount, float chance) {}
```

Like many other things, data maps are serialized and deserialized using [codecs]. This means that we need to provide a codec for our data map entry that we will use in a bit:

```java
public record ExampleData(float amount, float chance) {
    public static final Codec<ExampleData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("amount").forGetter(ExampleData::amount),
            Codec.floatRange(0, 1).fieldOf("chance").forGetter(ExampleData::chance)
    ).apply(instance, ExampleData::new));
}
```

Next, we create the data map itself:

```java
// In this example, we register the data map for the minecraft:item registry, hence we use Item as the generic.
// Adjust the types accordingly if you want to create a data map for a different registry.
public static final DataMapType<Item, ExampleData> EXAMPLE_DATA = DataMapType.builder(
        // The ID of the data map. Data map files for this data map will be located at
        // <yourmodid>:examplemod/data_maps/item/example_data.json.
        Identifier.fromNamespaceAndPath("examplemod", "example_data"),
        // The registry to register the data map for.
        Registries.ITEM,
        // The codec of the data map entries.
        ExampleData.CODEC
).build();
```

Finally, register the data map during the [`RegisterDataMapTypesEvent`][events] on the [mod event bus][modbus]:

```java
@SubscribeEvent // on the mod event bus
public static void registerDataMapTypes(RegisterDataMapTypesEvent event) {
    event.register(EXAMPLE_DATA);
}
```

### Syncing

Synced data maps will have their values synced to clients. A data map can be marked as synced by calling `#synced` on the builder, like so:

```java
public static final DataMapType<Item, ExampleData> EXAMPLE_DATA = DataMapType.builder(...)
        .synced(
                // The codec used for syncing. May be identical to the normal codec, but may also be
                // a codec with less fields, omitting parts of the object that are not required on the client.
                ExampleData.CODEC,
                // Whether the data map is mandatory or not. Marking a data map as mandatory will disconnect clients
                // that are missing the data map on their side; this includes vanilla clients.
                false
        ).build();
```

### Usage

As data maps can be used on any registry, they must be queried through `Holder`s, not through actual registry objects. Moreover, it will only work for reference holders, not `Direct` holders. However, most places will return a reference holder, for example `Registry#wrapAsHolder`, `Registry#getHolder` or the different `builtInRegistryHolder` methods, so in most situations this shouldn't be a problem.

You can then query the data map value via `Holder#getData(DataMapType)`. If an object does not have a data map value attached, the method will return `null`. Reusing our `ExampleData` from before, let's use them to heal the player whenever he picks them up:

```java
@SubscribeEvent // on the game event bus
public static void itemPickup(ItemEntityPickupEvent.Post event) {
    ItemStack stack = event.getOriginalStack();
    // Get a Holder<Item> via ItemStack#getItemHolder.
    Holder<Item> holder = stack.getItemHolder();
    // Get the data from the holder.
    //highlight-next-line
    ExampleData data = holder.getData(EXAMPLE_DATA);
    if (data != null) {
        // The values are present, so let's do something with them!
        Player player = event.getPlayer();
        if (player.getLevel().getRandom().nextFloat() > data.chance()) {
            player.heal(data.amount());
        }
    }
}
```

This process of course also works for all data maps provided by NeoForge.

## Advanced Data Maps

Advanced data maps are data maps that use `AdvancedDataMapType` instead of the standard `DataMapType` (of which `AdvancedDataMapType` is a subclass). They have some extra functionality, namely the ability to specify custom mergers and custom removers. Implementing this is highly recommended for data maps whose values are collections or collection-likes, such as `List`s or `Map`s.

While `DataMapType` has two generics `R` (registry type) and `T` (data map value type), `AdvancedDataMapType` has one more: `VR extends DataMapValueRemover<R, T>`. This generic allows for datagenning removers with proper type safety.

`AdvancedDataMapType`s are created using `AdvancedDataMapType#builder()` instead of `DataMapType#builder()`, returning an `AdvancedDataMapType.Builder`. This builder has two extra methods `#remover` and `#merger` for specifying removers and mergers (see below), respectively. All other functionality, including syncing, remains the same.

### Mergers

A merger can be used to handle conflicts between multiple data packs that attempt to add a value for the same object. The default merger (`DataMapValueMerger#defaultMerger`) will overwrite existing values (from e.g. data packs with lower priority) with new values, so a custom merger is necessary if this isn't the desired behavior.

The merger will be given the two conflicting values, as well as the objects the values are being attached to (as an `Either<TagKey<R>, ResourceKey<R>>`, since values can be attached to all objects in a tag or a single object) and the object's owning registry, and should return the value that should actually be attached. Generally, mergers should simply merge and not perform overwrites if possible (i.e. only if merging the normal way doesn't work). If a data pack wants to bypass the merger, it should specify the `replace` field on the object (see [Adding Values][add]).

Let's imagine a scenario where we have a data map that adds integers to items. We could then simply resolve conflicts by adding both values, like so:

```java
public class IntMerger implements DataMapValueMerger<Item, Integer> {
    @Override
    public Integer merge(Registry<Item> registry,
            Either<TagKey<Item>, ResourceKey<Item>> first, Integer firstValue,
            Either<TagKey<Item>, ResourceKey<Item>> second, Integer secondValue) {
        return firstValue + secondValue;
    }
}
```

This way, if one pack specifies the value 12 for `minecraft:carrot` and another pack specifies the value 15 for `minecraft:carrot`, then the final value for `minecraft:carrot` will be 27. If either of these objects specify `"replace": true`, then that object's value will be used. If both specify `"replace": true`, then the higher datapack's value is used.

Finally, don't forget to actually specify the merger in the builder, like so:

```java
// The types of the data map must match the type of the merger.
AdvancedDataMapType<Item, Integer> ADVANCED_MAP = AdvancedDataMapType.builder(...)
        .merger(new IntMerger())
        .build();
```

:::tip
NeoForge provides default mergers for lists, sets and maps in `DataMapValueMerger`.
:::

### Removers

Similar to mergers for more complex data, removers can be used for proper handling of `remove` clauses for an element. The default remover (`DataMapValueRemover.Default.INSTANCE`) will simply remove any and all information related to the specified object, so we want to use a custom remover to remove only parts of the object's data.

The codec passed to the builder (read on) will be used to decode remover instances. The remover will then be passed the value currently attached to the object and its source, and should return an `Optional` of the value to replace the old value. Alternatively, an empty `Optional` will lead to the value being actually removed.

Consider the following example of a remover that will remove a value with a specific key from a `Map<String, String>`-based data map:

```java
public record MapRemover(String key) implements DataMapValueRemover<Item, Map<String, String>> {
    public static final Codec<MapRemover> CODEC = Codec.STRING.xmap(MapRemover::new, MapRemover::key);
    
    @Override
    public Optional<Map<String, String>> remove(Map<String, String> value, Registry<Item> registry, Either<TagKey<Item>, ResourceKey<Item>> source, Item object) {
        final Map<String, String> newMap = new HashMap<>(value);
        newMap.remove(key);
        return Optional.of(newMap);
    }
}
```

With this remover in mind, consider the following data file:

```json5
{
    "values": {
        "minecraft:carrot": {
            "somekey1": "value1",
            "somekey2": "value2"
        }
    }
}
```

Now, consider this second data file that is placed at a higher priority than the first one:

```json5
{
    "remove": {
        // As the remover is decoded as a string, we can use a string as the value here.
        // If it were decoded as an object, we would have needed to use an object.
        "minecraft:carrot": "somekey1"
    }
}
```

That way, after both files are applied, the final result will be (an in-memory representation of) this:

```json5
{
    "values": {
        "minecraft:carrot": {
            "somekey2": "value2"
        }
    }
}
```

As with mergers, don't forget to add them to the builder. Note that we simply use the codec here:

```java
// We assume AdvancedData contains a Map<String, String> property of some sort.
AdvancedDataMapType<Item, AdvancedData> ADVANCED_MAP = AdvancedDataMapType.builder(...)
        .remover(MapRemover.CODEC)
        .build();
```

## Data Generation

Data maps can be [datagenned][datagen] by extending `DataMapProvider` and overriding `#gather` to create your entries. Reusing the `ExampleData` from before (with float values `amount` and `chance`), our datagen file could look something like this:

```java
public class MyDataMapProvider extends DataMapProvider {
    public MyDataMapProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(packOutput, lookupProvider);
    }
    
    @Override
    protected void gather() {
        // We create a builder for the EXAMPLE_DATA data map and add our entries using #add.
        this.builder(EXAMPLE_DATA)
                // We turn on replacing. Don't ever ship a mod like this! This is purely for educational purposes.
                .replace(true)
                // We add the value "amount": 10, "chance": 1 for all slabs. The boolean parameter controls
                // the "replace" field, which should always be false in a mod.
                .add(ItemTags.SLABS, new ExampleData(10, 1), false)
                // We add the value "amount": 5, "chance": 0.2 for apples.
                .add(Items.APPLE.builtInRegistryHolder(), new ExampleData(5, 0.2f), false) // Can also use Registry#wrapAsHolder to get the holder of a registry object
                // We remove wooden slabs again.
                .remove(ItemTags.WOODEN_SLABS)
                // We add a mod loaded condition for Botania, because why not.
                .conditions(new ModLoadedCondition("botania"));
    }
}
```

This would then result in the following JSON file:

```json5
{
    "replace": true,
    "values": {
        "#minecraft:slabs": {
            "amount": 10,
            "chance": 1.0
        },
        "minecraft:apple": {
            "amount": 5,
            "chance": 0.2
        }
    },
    "remove": [
        "#minecraft:wooden_slabs"
    ],
    "neoforge:conditions": [
        {
            "type": "neoforge:mod_loaded",
            "modid": "botania"
        }
    ]
}
```

Like all data providers, don't forget to add the provider to the event:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createProvider(MyDataMapProvider::new);
}
```

[builtin]: builtin.md
[codecs]: ../../../datastorage/codecs.md
[conditions]: ../conditions.md
[datagen]: ../../index.md#data-generation
[events]: ../../../concepts/events.md
[add]: #adding-values
[mergers]: #mergers
[modbus]: ../../../concepts/events.md#event-buses
[removers]: #removers
[tags]: ../tags.md

## resources/server/enchantments/builtin

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Built-In Enchantment Effect Components

Vanilla Minecraft provides numerous different types of enchantment effect components for use in [enchantment] definitions. This article will explain each, including their usage and in-code definition.

## Value Effect Components

_See also [Value Effect Components] on the Minecraft Wiki_

Value effect components are used for enchantments that alter a numerical value somewhere in the game, and are implemented by the class `EnchantmentValueEffect`. If a value is altered by more than one value effect component (for example, by multiple enchantments), all of their effects will apply.

Value effect components can be set to use any of these operations on their given values:
- `minecraft:set`: Overwrites the given level-based value.
- `minecraft:add`: Adds the specified level-based value to the old one.
- `minecraft:all_of`: Accepts a list of other value effects and applies them in the stated sequence.
- `minecraft:multiply`: Multiplies the specified level-based factor by the old one.
- `minecraft:remove_binomial`: Polls a given (level-based) chance using a binomial distibution. If it works, subtracts 1 from the value. Note that many values are effectively flags, being fully on at 1 and fully off at 0.
- `minecraft:exponential`: Polls a given (level-based) base and (level-based) exponent and then raises the base to that exponent. Multiplies the result with the old value.

The Sharpness enchantment uses `minecraft:damage`, a value effect component, as follows to achieve its effect:

<Tabs>
<TabItem value="sharpness.json" label="JSON">

```json5
"effects": {
    // The type of this effect component is "minecraft:damage".
    // This means that the effect will modify weapon damage.
    // See below for a list of more effect component types.
    "minecraft:damage": [
        {
            // A value effect that should be applied.
            // In this case, since there's only one, this value effect is just named "effect".
            "effect": {
                // The type of value effect to use. In this case, it is "minecraft:add", so the value (given below) will be added 
                // to the weapon damage value.
                "type": "minecraft:add",

                // The value block. In this case, the value is a LevelBasedValue that starts at 1 and increases by 0.5 every enchantment level.
                "value": {
                    "type": "minecraft:linear",
                    "base": 1.0,
                    "per_level_above_first": 0.5
                }
            }
        }
    ]
}
```

</TabItem>
<TabItem value="sharpness.datagen" label="Datagen">

```java
// Passed into 'effects' in an Enchantment during data generation
// See the Data Generation section of the Enchantments entry to learn more
DataComponentMap.builder().set(
    // Selects the "minecraft:damage" component.
    EnchantmentEffectComponents.DAMAGE,

    // Constructs a list of one conditional AddValue without any requirements.
    List.of(new ConditionalEffect<>(
        new AddValue(LevelBasedValue.perLevel(1.0F, 0.5F)),
        Optional.empty()))
).build()
```

</TabItem>
</Tabs>

The object within the `value` block is a [LevelBasedValue], which can be used to have a value effect component that changes the intensity of its effect by level.

The `EnchantmentValueEffect#process` method can be used to adjust values based on the provided numerical operations, like so:

```java
// `valueEffect` is an EnchantmentValueEffect instance.
// `enchantLevel` is an integer representing the level of the enchantment
float baseValue = 1.0;
float modifiedValue = valueEffect.process(enchantLevel, server.random, baseValue);
```

### Vanilla Enchantment Value Effect Component Types

#### Defined as `DataComponentType<EnchantmentValueEffect>`

- `minecraft:crossbow_charge_time`: Modifies the charge-up time of this crossbow in seconds. Used by Quick Charge.
- `minecraft:trident_spin_attack_strength`: Modifies the 'strength' of the spin attack of a trident (see `TridentItem#releaseUsing`). Used by Riptide.

#### Defined as `DataComponentType<List<ConditionalEffect<EnchantmentValueEffect>>>`

Armor related:
- `minecraft:armor_effectiveness`: Determines effectiveness of armor against this weapon on a scale of 0 (no protection) to 1 (normal protection). Used by Breach.
- `minecraft:damage_protection`: Each "point" of damage reduction reduces damage taken while wielding this item by 4%, to a maximum reduction of 80%. Used by Blast Protection, Feather Falling, Fire Protection, Protection, and Projectile Protection.

Attack related:
- `minecraft:damage`: Modifies attack damage with this weapon. Used by Sharpness, Impaling, Bane of Arthropods, Power, and Smite. 
- `minecraft:smash_damage_per_fallen_block`: Adds damage per block fallen to a mace. Used by Density.
- `minecraft:knockback`: Modifies the amount of knockback caused while wielding this weapon, measured in game units. Used by Knockback and Punch.
- `minecraft:mob_experience`: Modifies the amount of experience for killing a mob. Unused.

Durability related:
- `minecraft:item_damage`: Modifies the durability damage taken by the item. Values below 1 act as a chance that the item takes damage. Used by Unbreaking.
- `minecraft:repair_with_xp`: Causes the item to repair itself using XP gain, and determines how effective this is. Used by Mending.

Projectile related:
- `minecraft:ammo_use`: Modifies the amount of ammo used when firing a bow or crossbow. The value is clamped to an integer, so values below 1 will result in 0 ammo use. Used by Infinity.
- `minecraft:projectile_piercing`: Modifies the number of entities pierced by a projectile from this weapon. Used by Piercing.
- `minecraft:projectile_count`: Modifies the number of projectiles spawned when shooting this bow. Used by Multishot.
- `minecraft:projectile_spread`: Modifies the maximum spread of projectiles in degrees from the direction they were fired. Used by Multishot.
- `minecraft:trident_return_acceleration`: Causes the trident to return to its owner, and modifies the acceleration applied to this trident while doing so. Used by Loyalty.

Other:
- `minecraft:block_experience`: Modifies the amount of XP from breaking a block. Used by Silk Touch.
- `minecraft:fishing_time_reduction`: Reduces the time it takes for the bobber to sink while fishing with this rod by the given number of seconds. Used by Lure.
- `minecraft:fishing_luck_bonus`: Modifies the amount of [luck] used in the fishing loot table. Used by Luck of the Sea.

#### Defined as `DataComponentType<List<TargetedConditionalEffect<EnchantmentValueEffect>>>`

- `minecraft:equipment_drops`: Modifies the chance of equipment dropping from an entity killed by this weapon. Used by Looting.

## Location Based Effect Components

_See also: [Location Based Effect Components] on the Minecraft Wiki_

Location based effect components are components that implement `EnchantmentLocationBasedEffect`. These components define actions to take that need to know where in the level the wielder of the enchantment is. They operate using two major methods: `EnchantmentEntityEffect#onChangedBlock`, which is called when the enchanted item is equipped and when the wielder changes their `BlockPos`, and `onDeactivate`, which is called when the enchanted item is removed.

Here is an example which uses the `minecraft:attributes` location based effect component type to change the wielder's entity scale:

<Tabs>
<TabItem value="attribute.json" label="JSON">

```json5
// The type is "minecraft:attributes" (described below).
// In a nutshell, this applies an attribute modifier.
"minecraft:attributes": [
    {
        // This "amount" block is a LevelBasedValue.
        "amount": {
            "type": "minecraft:linear",
            "base": 1,
            "per_level_above_first": 1
        },

        // Which attribute to modify. In this case, modifies "minecraft:scale"
        "attribute": "minecraft:scale",
        // The unique identifier for this attribute modifier. Should not overlap with others, but doesn't need to be registered.
        "id": "examplemod:enchantment.size_change",
        // What operation to use on the attribute. Can be "add_value", "add_multiplied_base", or "add_multiplied_total".
        "operation": "add_value"
    }
],
```

</TabItem>
<TabItem value="attribute.datagen" label="Datagen">

```java
// Passed into the effects of an Enchantment during data generation
DataComponentMap.builder().set(
    // Specifies the "minecraft:attributes" component type.
    EnchantmentEffectComponents.ATTRIBUTES,

    // This component takes a list of these EnchantmentAttributeEffect objects.
    List.of(new EnchantmentAttributeEffect(
        Identifier.fromNamespaceAndPath("examplemod", "enchantment.size_change"),
        Attributes.SCALE,
        LevelBasedValue.perLevel(1F, 1F),
        AttributeModifier.Operation.ADD_VALUE
    ))
).build()
```

</TabItem>
</Tabs>

Vanilla adds the following location based events:

- `minecraft:all_of`: Runs a list of entity effects in sequence.
- `minecraft:apply_mob_effect`: Applies a [mob effect] to the affected mob.
- `minecraft:attribute`: Applies an [attribute modifier] to the wielder of the enchantment.
- `minecraft:change_item_damage`: Damages this item's durability.
- `minecraft:damage_entity`: Does damage to the affected entity. This stacks with attack damage if in an attacking context.
- `minecraft:explode`: Summons an explosion. 
- `minecraft:ignite`: Sets the entity on fire.
- `minecraft:apply_impulse`: Applies the specified velocity (broken into direction, coordinate, and magnitude) to the entity.
- `minecraft:apply_exhaustion`: Adds the specified amount of food exhaustion to the player.
- `minecraft:play_sound`: Plays a specified sound.
- `minecraft:replace_block`: Replaces a block at a given offset.
- `minecraft:replace_disk`: Replaces a disk of blocks.
- `minecraft:run_function`: Runs a specified [datapack function].
- `minecraft:set_block_properies`: Modifies the block state properties of the specified block.
- `minecraft:spawn_particles`: Spawns a particle.
- `minecraft:summon_entity`: Summons an entity.

### Vanilla Location Based Effect Component Types

#### Defined as `DataComponentType<List<ConditionalEffect<EnchantmentLocationBasedEffect>>>`

- `minecraft:location_changed`: Runs a location based effect when the wielder's Block Position changes and when this item is equipped. Used by Frost Walker and Soul Speed.

#### Defined as `DataComponentType<List<EnchantmentAttributeEffect>>`

- `minecraft:attributes`: Applies an attribute modifier to the wielder, and removes it when the enchanted item is no longer equipped.

## Entity Effect Components

_See also [Entity Effect Components] on the Minecraft Wiki._

Entity effect components are components that implement `EnchantmentEntityEffect`, an subtype of `EnchantmentLocationBasedEffect`. These override `EnchantmentLocationBasedEffect#onChangedBlock` to run `EnchantmentEntityEffect#apply` instead; this `apply` method is also directly invoked somewhere else in the codebase depending on the specific type of the component. This allows effects to occur without waiting for the wielder's block position to change.

All types of location based effect component are also valid types of entity effect component, except for `minecraft:attribute`, which is registered only as a location based effect component.

Here is an example of the JSON definition of one such component from the Fire Aspect enchantment:

<Tabs>
<TabItem value="fire.json" label="JSON">

```json5
// This component's type is "minecraft:post_attack" (see below).
"minecraft:post_attack": [
    {
        // Decides whether the "victim" of the attack, the "attacker", or the "damaging entity" (the projectile if there is one, attacker if not) recieves the effect.
        "affected": "victim",
        
        // Decides which enchantment entity effect to apply.
        "effect": {
            // The type of this effect is "minecraft:ignite".
            "type": "minecraft:ignite",
            // "minecraft:ignite" requires a LevelBasedValue as a duration for how long the entity will be ignited.
            "duration": {
                "type": "minecraft:linear",
                "base": 4.0,
                "per_level_above_first": 4.0
            }
        },

        // Decides who (the "victim", "attacker", or "damaging entity") must have the enchantment for it to take effect.
        "enchanted": "attacker",

        // An optional predicate which controls whether the effect applies.
        "requirements": {
            "condition": "minecraft:damage_source_properties",
            "predicate": {
                "is_direct": true
            }
        }
    }
]
```

</TabItem>
<TabItem value="fire.datagen" label="Datagen">

```java
// Passed into the effects of an Enchantment during data generation
DataComponentMap.builder().set(
    // Specifies the "minecraft:post_attack" component type.
    EnchantmentEffectComponents.POST_ATTACK,

    // Defines the data for this component. In this case, a list of one TargetedConditionalEffect.
    List.of(
        new TargetedConditionalEffect<>(

            // Determines the "enchanted" field.
            EnchantmentTarget.ATTACKER,

            // Determines the "affected" field.
            EnchantmentTarget.VICTIM,

            // The enchantment entity effect.
            new Ignite(LevelBasedValue.perLevel(4.0F, 4.0F)),

            // The "requirements" clause. 
            // In this case, the only optional part activated is the isDirect boolean flag.
            Optional.of(
                new DamageSourceCondition(
                    Optional.of(
                        new DamageSourcePredicate(
                            List.of(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(true)
                        )
                    )
                )
            )
        )
    )
).build()
```

</TabItem>
</Tabs>

Here, the entity effect component is `minecraft:post_attack`. Its effect is `minecraft:ignite`, which is implemented by the `Ignite` record. This record's implementation of `EnchantmentEntityEffect#apply` sets the target entity on fire.

### Vanilla Enchantment Entity Effect Component Types

#### Defined as `DataComponentType<List<ConditionalEffect<EnchantmentEntityEffect>>>`

- `minecraft:post_piercing_attack`: Runs an entity effect when a living entity lunges forward. Used by Lunge.
- `minecraft:hit_block`: Runs an entity effect when an entity (for example, a projectile) hits a block. Used by Channeling.
- `minecraft:tick`: Runs an entity effect each tick. Used by Soul Speed.
- `minecraft:projectile_spawned`: Runs an entity effect after a projectile entity has been spawned from a bow or crossbow. Used by Flame.

#### Defined as `DataComponentType<List<TargetedConditionalEffect<EnchantmentEntityEffect>>>`

- `minecraft:post_attack`: Runs an entity effect after an attack damages an entity. Used by Bane of Arthropods, Channeling, Fire Aspect, Thorns, and Wind Burst.

For more detail on each of these, please look at the [relevant minecraft wiki page].

## Other Vanilla Enchantment Component Types

#### Defined as `DataComponentType<List<ConditionalEffect<DamageImmunity>>>`

- `minecraft:damage_immunity`: Applies immunity to a specified damage type. Used by Frost Walker.

#### Defined as `DataComponentType<Unit>`

- `minecraft:prevent_equipment_drop`: Prevents this item from being dropped by a player when dying. Used by Curse of Vanishing.
- `minecraft:prevent_armor_change`: Prevents this item from being unequipped from an armor slot. Used by Curse of Binding.

#### Defined as `DataComponentType<List<CrossbowItem.ChargingSounds>>`

- `minecraft:crossbow_charge_sounds`: Determines the sound events that occur when charging a crossbow. Each entry represents one level of the enchantment.

#### Defined as `DataComponentType<List<Holder<SoundEvent>>>`

- `minecraft:trident_sound`: Determines the sound events that occur when using a trident. Each entry represents one level of the enchantment.

[enchantment]: index.md
[Value Effect Components]: https://minecraft.wiki/w/Enchantment_definition#Components_with_value_effects
[Entity Effect Components]: https://minecraft.wiki/w/Enchantment_definition#Components_with_entity_effects
[Location Based Effect Components]: https://minecraft.wiki/w/Enchantment_definition#location_changed
[text component]: ../../client/i18n.md
[LevelBasedValue]: ../loottables/index.md#number-provider
[Attribute Effect Component]: https://minecraft.wiki/w/Enchantment_definition#Attribute_effects
[datapack function]: https://minecraft.wiki/w/Function_(Java_Edition)
[luck]: https://minecraft.wiki/w/Luck
[mob effect]: ../../../items/mobeffects.md
[attribute modifier]: ../../../entities/attributes.md#attribute-modifiers
[relevant minecraft wiki page]: https://minecraft.wiki/w/Enchantment_definition#Components_with_entity_effects

## resources/server/enchantments/index

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Enchantments

Enchantments are special effects that can be applied to tools and other items. As of 1.21, enchantments are stored on items as [Data Components], are defined in JSON, and are comprised of so-called enchantment effect components. During the game, the enchantments on a particular item are contained within the `DataComponents.ENCHANTMENTS` component, in an `ItemEnchantments` instance.

A new enchantment can be added by creating a JSON file in your namespace's `enchantment` datapack subfolder. For example, to create an enchantment called `examplemod:example_enchant`, one would create a file `data/examplemod/enchantment/example_enchantment.json`. 

## Enchantment JSON Format

```json5
{
    // The text component that will be used as the in-game name of the enchantment.
    // Can be a translation key or a literal string. 
    // Remember to translate this in your lang file if you use a translation key!
    "description": {
        "translate": "enchantment.examplemod.enchant_name"
    },
    
    // Which items this enchantment can be applied to.
    // Can be either an item id, such as "minecraft:trident",
    // or a list of item ids, such as ["examplemod:red_sword", "examplemod:blue_sword"]
    // or an item tag, such as "#examplemod:enchantable/enchant_name".
    // Note that this doesn't cause the enchantment to appear for these items in the enchanting table.
    "supported_items": "#examplemod:enchantable/enchant_name",

    // (Optional) Which items this enchantment appears for in the enchanting table or as part of an enchantment provider.
    // For the enchantment to be shown in an enchantment table for the item, it must be added to the `minecraft:in_enchanting_table` tag.
    // `minecraft:non_treasure` entries are already in the enchantment table tag by default.
    // Can be an item, list of items, or item tag.
    // If left unspecified, this is the same as `supported_items`.
    "primary_items": [
        "examplemod:item_a",
        "examplemod:item_b"
    ],

    // (Optional) Which enchantments are incompatible with this one.
    // Can be an enchantment id, such as "minecraft:sharpness",
    // or a list of enchantment ids, such as ["minecraft:sharpness", "minecraft:fire_aspect"],
    // or enchantment tag, such as "#examplemod:exclusive_to_enchant_name".
    // Incompatible enchantments will not be added to the same item by vanilla mechanics.
    "exclusive_set": "#examplemod:exclusive_to_enchant_name",
    
    // The likelihood that this enchantment will appear in the Enchanting Table. 
    // Bounded by [1, 1024].
    "weight": 6,
    
    // The maximum level this enchantment is allowed to reach.
    // Bounded by [1, 255].
    "max_level": 3,
    
    // The maximum cost of this enchantment, measured in "enchanting power". 
    // This corresponds to, but is not equivalent to, the threshold in levels the player needs to meet to bestow this enchantment.
    // See below for details.
    // The actual cost will be between this and the min_cost.
    "max_cost": {
        "base": 45,
        "per_level_above_first": 9
    },
    
    // Specifies the minimum cost of this enchantment; otherwise as above.
    "min_cost": {
        "base": 2,
        "per_level_above_first": 8
    },

    // The cost that this enchantment adds to repairing an item in an anvil in levels. The cost is multiplied by enchantment level.
    // If an item has a DataComponentTypes.STORED_ENCHANTMENTS component, the cost is halved. In vanilla, this only applies to enchanted books.
    // Bounded by [1, inf).
    "anvil_cost": 2,
    
    // (Optional) A list of slot groups this enchantment provides effects in. 
    // A slot group is defined as one of the possible values of the EquipmentSlotGroup enum.
    // In vanilla, these are: `any`, `hand`, `mainhand`, `offhand`, `armor`, `feet`, `legs`, `chest`, `head`, and  `body`.
    "slots": [
        "mainhand"
    ],

    // The effects that this enchantment provides as a map of enchantment effect components (read on).
    "effects": {
        "examplemod:custom_effect": [
            {
                "effect": {
                    "type": "minecraft:add",
                    "value": {
                        "type": "minecraft:linear",
                        "base": 1,
                        "per_level_above_first": 1
                    }
                }
            }
        ]
    }
}
```

### Enchantment Costs and Levels

The `max_cost` and `min_cost` fields specify boundaries for how much enchanting power is needed to create this enchantment. There is a somewhat convoluted procedure to actually make use of these values, however.

First, the table takes into account the return value of `IBlockExtension#getEnchantPowerBonus()` for the surrounding blocks. From this, it calls `EnchantmentHelper#getEnchantmentCost` to derive a 'base level' for each slot. This level is shown in-game as the green numbers besides the enchantments in the menu. For each enchantment, the base level is modified twice by a random value derived from the item's enchantability (its return value extracted from the `DataComponents#ENCHANTABLE` data component via `Enchantable#value`), like so:

`(Modified Level) = (Base Level) + random.nextInt(e / 4 + 1) + random.nextInt(e / 4 + 1)`, where `e` is the enchantability score.

This modified level is adjusted up or down by a random 15%, and then is finally used to choose an enchantment. This level must fall within your enchantment's cost bounds in order for it to be chosen.

In practical terms, this means that the cost values in your enchantment definition might be above 30, sometimes far above. For example, with an enchantability 10 item, the table could produce enchantments up to 1.15 * (30 + 2 * (10 / 4) + 1) = 40 cost. 

## Enchantment Effect Components

Enchantment effect components are specially-registered [Data Components] that determine how an enchantment functions. The type of the component defines its effect, while the data it contains is used to inform or modify that effect. For instance, the `minecraft:damage` component modifies the damage that a weapon deals by an amount determined by its data.

Vanilla defines various [built-in enchantment effect components], which are used to implement all vanilla enchantments.

### Custom Enchantment Effect Components

The logic of applying a custom enchantment effect component must be entirely implemented by its creator. First, you should define a class or record to hold the information you need to implement a given effect. For example, let's make an example record class `Increment`:

```java
// Define an example data-bearing record.
public record Increment(int value) {
    public static final Codec<Increment> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.fieldOf("value").forGetter(Increment::value)
            ).apply(instance, Increment::new)
    );

    public int add(int x) {
        return value() + x;
    }
}
```

Enchantment effect component types must be [registered] to `BuiltInRegistries.ENCHANTMENT_EFFECT_COMPONENT_TYPE`, which takes a `DataComponentType<?>`. For example, you could register an enchantment effect component that can store an `Increment` object as follows:

```java
// In some registration class
public static final DeferredRegister.DataComponents ENCHANTMENT_COMPONENT_TYPES =
    DeferredRegister.createDataComponents(BuiltInRegistries.ENCHANTMENT_EFFECT_COMPONENT_TYPE, "examplemod");

public static final Supplier<DataComponentType<Increment>> INCREMENT =
    ENCHANTMENT_COMPONENT_TYPES.registerComponentType(
        "increment",
        builder -> builder.persistent(Increment.CODEC)
    );
```

Now, we can implement some game logic that makes use of this component to alter an integer value:

```java
// Somewhere in game logic where an `itemStack` is available.
// `INCREMENT` is the enchantment component type holder defined above.
// `value` is an integer.
AtomicInteger atomicValue = new AtomicInteger(value);

EnchantmentHelper.runIterationOnItem(stack, (enchantmentHolder, enchantLevel) -> {
    // Acquire the Increment instance from the enchantment holder (or null if this is a different enchantment)
    Increment increment = enchantmentHolder.value().effects().get(INCREMENT.get());

    // If this enchant has an Increment component, use it.
    if(increment != null){
        atomicValue.set(increment.add(atomicValue.get()));
    }
});

int modifiedValue = atomicValue.get();
// Use the now-modified value elsewhere in your game logic.
```

First, we invoke one of the overloads of `EnchantmentHelper#runIterationOnItem`. This function accepts an `EnchantmentHelper.EnchantmentVisitor`, which is a functional interface that accepts an enchantment and its level, and is invoked on all of the enchantments that the given itemstack has (essentially a `BiConsumer<Holder<Enchantment>, Integer>`).

To actually perform the adjustment, use the provided `Increment#add` method. Since this is inside of a lambda expression, we need to use a type that can be updated atomically, such as `AtomicInteger`, to modify this value. This also permits multiple `INCREMENT` components to run on the same item and stack their effects, like what happens in vanilla.

### `ConditionalEffect`
Wrapping the type in `ConditionalEffect<?>` allows the enchantment effect component to optionally take effect based on a given [LootContext].

`ConditionalEffect` provides `ConditionalEffect#matches(LootContext context)`, which returns whether the effect should be allowed to run based on its internal `Optional<LootItemConditon>`, and handled serialization and deserialization of its `LootItemCondition`.

Vanilla adds an additional helper method to further streamline the process of checking these conditions: `Enchantment#applyEffects()`. This method takes a `List<ConditionalEffect<T>>`, evaluates the conditions, and runs a `Consumer<T>` on each `T` contained by a `ConditionalEffect` whose condition was met. Since many vanilla enchantment effect components are defined as a `List<ConditionalEffect<?>>`, these can be directly plugged into the helper method like so:

```java
// `enchant` is an Enchantment instance.
// `lootContext` is a LootContext instance.
enchant.applyEffects(
    // Or whichever other List<ConditionalEffect<T>> you want
    enchant.getEffects(EnchantmentEffectComponents.KNOCKBACK),
    // The context to test the conditions against
    lootContext,
    (effectData) -> // Use the effectData (in this example, an EnchantmentValueEffect) however you want.
);
```

Registering a custom `ConditionalEffect`-wrapped enchantment effect component type can be done as follows:

```java
public static final DeferredHolder<DataComponentType<?>, DataComponentType<ConditionalEffect<Increment>>> CONDITIONAL_INCREMENT =
    ENCHANTMENT_COMPONENT_TYPES.register("conditional_increment",
        () -> DataComponentType.ConditionalEffect<Increment>builder()
            // The ContextKeySet needed depends on what the enchantment is supposed to do.
            // This might be one of ENCHANTED_DAMAGE, ENCHANTED_ITEM, ENCHANTED_LOCATION, ENCHANTED_ENTITY, or HIT_BLOCK
            // since all of these bring the enchantment level into context (along with whatever other information is indicated).
            .persistent(ConditionalEffect.codec(Increment.CODEC, LootContextParamSets.ENCHANTED_DAMAGE))
            .build());
```

The parameters to `ConditionalEffect.codec` are the codec for the generic `ConditionalEffect<T>`, followed by some `ContextKeySet` entry.

## Enchantment Data Generation

Enchantment JSON files can be created automatically using the [data generation] system by passing a `RegistrySetBuilder` into `DatapackBuiltInEntriesProvider` via `GatherDataEvent#createDatapackRegistryObjects`. The JSON will be placed in `<project root>/src/generated/data/<modid>/enchantment/<path>.json`.

For more information on how `RegistrySetBuilder` and `DatapackBuiltinEntriesProvider` work, please see the article on [Data Generation for Datapack Registries]. 

<Tabs>
<TabItem value="datagen" label="Datagen">

```java

// This RegistrySetBuilder should be passed into a DatapackBuiltinEntriesProvider in your `GatherDataEvent`s listener.
RegistrySetBuilder BUILDER = new RegistrySetBuilder();
BUILDER.add(
    Registries.ENCHANTMENT,
    bootstrap -> bootstrap.register(
        // Define the ResourceKey for our enchantment.
        ResourceKey.create(
            Registries.ENCHANTMENT,
            Identifier.fromNamespaceAndPath("examplemod", "example_enchantment")
        ),
        new Enchantment(
            // The text Component that specifies the enchantment's name.
            Component.literal("Example Enchantment"),  
            
            // Specify the enchantment definition of for our enchantment.
            new Enchantment.EnchantmentDefinition(
                // A HolderSet of Items that the enchantment will be compatible with.
                HolderSet.direct(...), 

                // An Optional<HolderSet> of items that the enchantment considers "primary".
                Optional.empty(), 

                // The weight of the enchantment.
                30, 

                // The maximum level this enchantment can be.
                3, 

                // The minimum cost of the enchantment. The first parameter is base cost, the second is cost per level.
                Enchantment.dynamicCost(3, 1), 

                // The maximum cost of the enchantment. As above.
                Enchantment.dynamicCost(4, 2), 

                // The anvil cost of the enchantment.
                2, 

                // A list of EquipmentSlotGroups that this enchantment has effects in.
                List.of(EquipmentSlotGroup.ANY) 
            ),
            // A HolderSet of incompatible other enchantments.
            HolderSet.empty(), 

            // A DataComponentMap of the enchantment effect components associated with this enchantment and their values.
            DataComponentMap.builder() 
                .set(MY_ENCHANTMENT_EFFECT_COMPONENT_TYPE, new ExampleData())
                .build()
        )
    )
);

```

</TabItem>

<TabItem value="json" label="JSON" default>

```json5
// For more detail on each entry, please check the section above on the enchantment JSON format.
{
    // The anvil cost of the enchantment.
    "anvil_cost": 2,

    // The text Component that specifies the enchantment's name.
    "description": "Example Enchantment",

    // A map of the effect components associated with this enchantment and their values.
    "effects": {
        // <effect components>
    },

    // The maximum cost of the enchantment.
    "max_cost": {
        "base": 4,
        "per_level_above_first": 2
    },

    // The maximum level this enchantment can be.
    "max_level": 3,

    // The minimum cost of the enchantment.
    "min_cost": {
        "base": 3,
        "per_level_above_first": 1
    },

    // A list of EquipmentSlotGroup aliases that this enchantment has effects in.
    "slots": [
        "any"
    ],

    // The set of items that this enchantment can be applied to using an anvil.
    "supported_items": /* <supported item list> */,

    // The weight of this enchantment.
    "weight": 30
}
```

</TabItem>
</Tabs>

[Data Components]: ../../../items/datacomponents.md
[Codec]: ../../../datastorage/codecs.md
[Enchantment definition Minecraft wiki page]: https://minecraft.wiki/w/Enchantment_definition
[registered]: ../../../concepts/registries.md
[Predicate]: https://minecraft.wiki/w/Predicate
[data generation]: ../../../resources/index.md#data-generation
[Data Generation for Datapack Registries]: https://docs.neoforged.net/docs/concepts/registries/#data-generation-for-datapack-registries
[relevant minecraft wiki page]: https://minecraft.wiki/w/Enchantment_definition#Entity_effects
[built-in enchantment effect components]: builtin.md
[LootContext]: ../loottables/index.md#loot-context

## resources/server/loottables/custom

# Custom Loot Objects

Due to the complexity of the loot table system, there are several [registries] at work, all of which can be used by a modder to add more behavior.

All loot table related registries follow a similar pattern. To add a new registry entry, you generally extend some class or implement some interface that holds your functionality. Then, you define a [codec] for serialization, and register that codec to the corresponding registry, using `DeferredRegister` like normal. This goes along with the "one base object, many instances" approach most registries (for example also blocks/blockstates and items/item stacks) use.

## Custom Loot Entries

To create a custom loot entry, extend `LootPoolEntryContainer` or one of its two direct subclasses, `LootPoolSingletonContainer` or `CompositeEntryBase`. For the sake of example, we want to create a loot entry that returns the drops of a [entity] - this is purely for example purposes, in practice it would be more ideal to directly reference the other loot table. Let's start by creating our loot entry class:

```java
// We extend LootPoolSingletonContainer since we have a "finite" set of drops.
// Some of this code is adapted from NestedLootTable.
public class EntityLootEntry extends LootPoolSingletonContainer {
    public static final MapCodec<EntityLootEntry> CODEC = RecordCodecBuilder.mapCodec(inst ->
        // Add our own fields.
        inst.group(
                        // A value referencing an entity type id.
                        BuiltInRegistries.ENTITY_TYPE.holderByNameCodec().fieldOf("entity").forGetter(e -> e.entity)
                )
                // Add common fields: weight, display, conditions, and functions.
                .and(singletonFields(inst))
                .apply(inst, EntityLootEntry::new)
    );

    // A Holder for the entity type we want to roll the other table for.
    private final Holder<EntityType<?>> entity;

    // It is common practice to have a private constructor and have a static factory method.
    // This is because weight, quality, conditions, and functions are supplied by a lambda below.
    private EntityLootEntry(Holder<EntityType<?>> entity, int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions) {
        // Pass lambda-provided parameters to super.
        super(weight, quality, conditions, functions);
        // Set our values.
        this.entity = entity;
    }

    // Static builder method, accepting our custom parameters and combining them with a lambda that supplies the values common to all entries.
    public static LootPoolSingletonContainer.Builder<?> entityLoot(Holder<EntityType<?>> entity) {
        // Use the static simpleBuilder() method defined in LootPoolSingletonContainer.
        return simpleBuilder((weight, quality, conditions, functions) -> new EntityLootEntry(entity, weight, quality, conditions, functions));
    }

    // This is where the magic happens. To add an item stack, we generally call #accept on the consumer.
    // However, in this case, we let #getRandomItems do that for us.
    @Override
    public void createItemStack(Consumer<ItemStack> consumer, LootContext context) {
        // Get the entity's loot table. If it doesn't exist, an empty loot table will be returned, so null-checking is not necessary.
        LootTable table = context.getLevel().reloadableRegistries().getLootTable(entity.value().getDefaultLootTable());
        // Use the raw version here, because vanilla does it too. :P
        // #getRandomItemsRaw calls consumer#accept for us on the results of the roll.
        table.getRandomItemsRaw(context, consumer);
    }

    // Tells the entry what to use for serialization.
    @Override
    public MapCodec<EntityLootEntry> codec() {
        return CODEC;
    }
}
```

We then use the map codec in [registration][registries]:

```java
public static final DeferredRegister<MapCodec<? extends LootPoolEntryContainer>> LOOT_POOL_ENTRY_TYPES =
        DeferredRegister.create(Registries.LOOT_POOL_ENTRY_TYPE, ExampleMod.MOD_ID);

public static final Supplier<MapCodec<EntityLootEntry>> ENTITY_LOOT =
        LOOT_POOL_ENTRY_TYPES.register("entity_loot", () -> EntityLootEntry.CODEC);
```

## Custom Number Providers

To create a custom number provider, implement the `NumberProvider` interface. For the sake of example, let's assume we want to create a number provider that changes the sign of the provided number:

```java
// We accept another number provider as our base.
public record InvertedSignProvider(NumberProvider base) implements NumberProvider {
    public static final MapCodec<InvertedSignProvider> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            NumberProviders.CODEC.fieldOf("base").forGetter(InvertedSignProvider::base)
    ).apply(inst, InvertedSignProvider::new));

    // Return a float value. Use the context and the record parameters as needed.
    @Override
    public float getFloat(LootContext context) {
        return -this.base.getFloat(context);
    }

    // Return an int value. Use the context and the record parameters as needed.
    // Overriding this is optional, the default implementation will round the result of #getFloat.
    @Override
    public int getInt(LootContext context) {
        return -this.base.getInt(context);
    }

    // Return a set of the loot context params used by this provider. See below for more information.
    // Since we have a base value, we just defer to the base.
    @Override
    public Set<ContextKey<?>> getReferencedContextParams() {
        return this.base.getReferencedContextParams();
    }

    // Tells the provider what to use for serialization.
    @Override
    public MapCodec<InvertedSignProvider> codec() {
        return CODEC;
    }
}
```

Like with custom loot entries, we then use this codec in [registration][registries]:

```java
public static final DeferredRegister<MapCodec<? extends NumberProvider>> LOOT_NUMBER_PROVIDER_TYPES =
        DeferredRegister.create(Registries.LOOT_NUMBER_PROVIDER_TYPE, ExampleMod.MOD_ID);

public static final Supplier<MapCodec<? extends NumberProvider>> INVERTED_SIGN =
        LOOT_NUMBER_PROVIDER_TYPES.register("inverted_sign", () -> InvertedSignProvider.CODEC);
```

## Custom Level-Based Values

Custom `LevelBasedValue`s can be created by implementing the `LevelBasedValue` interface in a record. Again, for the sake of example, let's assume that we want to invert the output of another `LevelBasedValue`:

```java
public record InvertedSignLevelBasedValue(LevelBasedValue base) implements LevelBaseValue {
    public static final MapCodec<InvertedLevelBasedValue> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            LevelBasedValue.CODEC.fieldOf("base").forGetter(InvertedLevelBasedValue::base)
    ).apply(inst, InvertedLevelBasedValue::new));

    // Perform our operation.
    @Override
    public float calculate(int level) {
        return -this.base.calculate(level);
    }

    // Tells the value what to use for serialization.
    @Override
    public MapCodec<InvertedLevelBasedValue> codec() {
        return CODEC;
    }
}
```

And again, we then use the codec in [registration][registries]:

```java
public static final DeferredRegister<MapCodec<? extends LevelBasedValue>> LEVEL_BASED_VALUES =
        DeferredRegister.create(Registries.ENCHANTMENT_LEVEL_BASED_VALUE_TYPE, ExampleMod.MOD_ID);

public static final Supplier<MapCodec<InvertedSignLevelBasedValue>> INVERTED_SIGN =
        LEVEL_BASED_VALUES.register("inverted_sign", () -> InvertedSignLevelBasedValue.CODEC);
```

## Custom Loot Conditions

To get started, we create our loot item condition class that implements `LootItemCondition`. For the sake of example, let's assume we only want the condition to pass if the player killing the mob has a certain xp level:

```java
public record HasXpLevelCondition(int level) implements LootItemCondition {
    // Add the context we need for this condition. In our case, this will be the xp level the player must have.
    public static final MapCodec<HasXpLevelCondition> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Codec.INT.fieldOf("level").forGetter(HasXpLevelCondition::level)
    ).apply(inst, HasXpLevelCondition::new));
    
    // Evaluates the condition here. Get the required loot context parameters from the provided LootContext.
    // In our case, we want the KILLER_ENTITY to have at least our required level.
    @Override
    public boolean test(LootContext context) {
        @Nullable
        Entity entity = context.getOptionalParameter(LootContextParams.KILLER_ENTITY);
        return entity instanceof Player player && player.experienceLevel >= level; 
    }
    
    // Tell the game what parameters we expect from the loot context. Used in validation.
    @Override
    public Set<ContextKey<?>> getReferencedContextParams() {
        return ImmutableSet.of(LootContextParams.KILLER_ENTITY);
    }

    // Tells the condition what to use for serialization.
    @Override
    public MapCodec<HasXpLevelCondition> codec() {
        return CODEC;
    }
}
```

We can [register][registries] the map codec to the registry:

```java
public static final DeferredRegister<MapCodec<? extends LootItemCondition>> LOOT_CONDITION_TYPES =
        DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, ExampleMod.MOD_ID);

public static final Supplier<MapCodec<HasXpLevelCondition>> MIN_XP_LEVEL =
        LOOT_CONDITION_TYPES.register("min_xp_level", () -> HasXpLevelCondition.CODEC);
```

## Custom Loot Functions

To get started, we create our own class extending `LootItemFunction`. `LootItemFunction` extends `BiFunction<ItemStack, LootContext, ItemStack>`, so what we want is to use the existing item stack and the loot context to return a new, modified item stack. However, almost all loot functions don't directly extend `LootItemFunction`, but extend `LootItemConditionalFunction` instead. This class has built-in functionality for applying loot conditions to the function - the function is only applied if the loot conditions apply. For the sake of example, let's apply a random enchantment with a specified level to the item:

```java
// Code adapted from vanilla's EnchantRandomlyFunction class.
// LootItemConditionalFunction is an abstract class, not an interface, so we cannot use a record here.
public class RandomEnchantmentWithLevelFunction extends LootItemConditionalFunction {
    // Our context: an optional list of enchantments, and a level.
    private final Optional<HolderSet<Enchantment>> enchantments;
    private final int level;
    // Our codec.
    public static final MapCodec<RandomEnchantmentWithLevelFunction> CODEC =
            // #commonFields adds the conditions field.
            RecordCodecBuilder.mapCodec(inst -> commonFields(inst).and(inst.group(
                    RegistryCodecs.homogeneousList(Registries.ENCHANTMENT).optionalFieldOf("enchantments").forGetter(e -> e.enchantments),
                    Codec.INT.fieldOf("level").forGetter(e -> e.level)
            ).apply(inst, RandomEnchantmentWithLevelFunction::new));
    
    public RandomEnchantmentWithLevelFunction(List<LootItemCondition> conditions, Optional<HolderSet<Enchantment>> enchantments, int level) {
        super(conditions);
        this.enchantments = enchantments;
        this.level = level;
    }
    
    // Run our enchantment application logic. Most of this is copied from EnchantRandomlyFunction#run.
    @Override
    public ItemStack run(ItemStack stack, LootContext context) {
        RandomSource random = context.getRandom();
        List<Holder<Enchantment>> stream = this.enchantments
                .map(HolderSet::stream)
                .orElseGet(() -> context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().map(Function.identity()))
                .filter(e -> e.value().canEnchant(stack))
                .toList();
        Optional<Holder<Enchantment>> optional = Util.getRandomSafe(list, random);
        if (optional.isEmpty()) {
            LOGGER.warn("Couldn't find a compatible enchantment for {}", stack);
        } else {
            Holder<Enchantment> enchantment = optional.get();
            if (stack.is(Items.BOOK)) {
                stack = new ItemStack(Items.ENCHANTED_BOOK);
            }
            stack.enchant(enchantment, Mth.nextInt(random, enchantment.value().getMinLevel(), enchantment.value().getMaxLevel()));
        }
        return stack;
    }

    // Tells the function what to use for serialization.
    @Override
    public MapCodec<RandomEnchantmentWithLevelFunction> codec() {
        return CODEC;
    }
}
```

We can then [register][registries] the map codec to the registry:

```java
public static final DeferredRegister<MapCodec<? extends LootItemFunction>> LOOT_FUNCTION_TYPES =
        DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, ExampleMod.MOD_ID);

public static final Supplier<MapCodec<RandomEnchantmentWithLevelFunction>> RANDOM_ENCHANTMENT_WITH_LEVEL =
        LOOT_FUNCTION_TYPES.register("random_enchantment_with_level", () -> RandomEnchantmentWithLevelFunction.CODEC);
```

[codec]: ../../../datastorage/codecs.md
[entity]: ../../../entities/index.md
[registries]: ../../../concepts/registries.md#methods-for-registering

## resources/server/loottables/glm

# Global Loot Modifiers

Global Loot Modifiers, or GLMs for short, are a data-driven way to modify drops without the need to overwrite dozens or hundreds of vanilla loot tables, or to handle effects that would require interactions with another mod's loot tables without knowing what mods are loaded.

GLMs work by first rolling the associated [loot table][loottable] and then applying the GLM to the result of rolling the table. GLMs are also stacking, rather than last-load-wins, to allow for multiple mods to modify the same loot table, this is similar to [tags].

To register a GLM, you will need three things:

- A JSON file representing your loot modifier. This file contains all the data for your modification, allowing data packs to tweak your effect. It is located at `data/<namespace>/loot_modifiers/<path>.json`.
- A class that implements `IGlobalLootModifier` or extends `LootModifier` (which in turn implements `IGlobalLootModifier`). This class contains the code that makes the modifier work.
- A map [codec] to encode and decode your loot modifier class. Usually, this is implemented as a `public static final` field in the loot modifier class.

## The Loot Modifier JSON

This file contains all values related to your modifier, for example chances to apply, what items to add, etc. The JSON can be found at `data/<namespace>/loot_modifiers/<path>.json`, where `<namespace>` and `<path>` are parts of the unique [`Identifier`][identifier]. It is recommended to avoid hard-coded values wherever possible so that data pack makers can adjust balance if they wish to. A loot modifier must contain at least two fields and may contain more, depending on the circumstances:

- The `type` field contains the registry name of the loot modifier.
- The `conditions` field is a list of loot table conditions for this modifier to activate.
- Additional properties may be required or optional, depending on the used codec.

:::tip
A common use case for GLMs is to add extra loot to one specific loot table. To achieve this, the [`neoforge:loot_table_id` condition][loottableid] can be used.
:::

An example usage may look something like this:

```json5
{
    // This is the registry name of the loot modifier
    "type": "examplemod:my_loot_modifier",
    "conditions": [
        // Loot table conditions here
    ],
    // An optional property typically provided by loot modifiers
    // to denote the order that the modifiers should be applied,
    // from highest to lowest.
    // Typically defaults to 1000.
    "priority": 900,
    // Extra properties specified by the codec
    "field1": "somestring",
    "field2": 10,
    "field3": "minecraft:dirt"
}
```

## `IGlobalLootModifier` and `LootModifier`

To actually apply the loot modifier to the loot table, a `IGlobalLootModifier` implementation must be specified. In most cases, you will want to use the `LootModifier` subclass, which handles things like conditions and priorities for you. To get started, we extend `LootModifier` in our loot modifier class:

```java
// We cannot use a record because records cannot extend other classes.
public class MyLootModifier extends LootModifier {
    // See below for how the codec works.
    public static final MapCodec<MyLootModifier> CODEC = ...;
    // Our extra properties.
    private final String field1;
    private final int field2;
    private final Item field3;
    
    // First constructor parameter is the list of conditions. The rest is our extra properties.
    public MyLootModifier(LootItemCondition[] conditions, int priority, String field1, int field2, Item field3) {
        super(conditions, priority);
        this.field1 = field1;
        this.field2 = field2;
        this.field3 = field3;
    }
    
    // Return our codec here.
    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
    
    // This is where the magic happens. Use your extra properties here if needed.
    // Parameters are the existing loot, and the loot context.
    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        // Add your items to generatedLoot here.
        return generatedLoot;
    }
}
```

:::info
The returned list of drops from a modifier is fed into other modifiers in `priority` order, from highest to lowest. As such, modified loot can and should be expected to be modified by another loot modifier.
:::

## The Loot Modifier Codec

To tell the game about the existence of our loot modifier, we must define and [register] a [codec] for it. Reiterating on our previous example with the three fields, this would look something like this:

```java
public static final MapCodec<MyLootModifier> CODEC = RecordCodecBuilder.mapCodec(inst -> 
        // LootModifier#codecStart adds the conditions field.
        LootModifier.codecStart(inst).and(inst.group(
                Codec.STRING.fieldOf("field1").forGetter(e -> e.field1),
                Codec.INT.fieldOf("field2").forGetter(e -> e.field2),
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("field3").forGetter(e -> e.field3)
        )).apply(inst, MyLootModifier::new)
);
```

Then, we [register] the codec to the registry:

```java
public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> GLOBAL_LOOT_MODIFIER_SERIALIZERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, ExampleMod.MOD_ID);

public static final Supplier<MapCodec<MyLootModifier>> MY_LOOT_MODIFIER =
        GLOBAL_LOOT_MODIFIER_SERIALIZERS.register("my_loot_modifier", () -> MyLootModifier.CODEC);
```

## Builtin Loot Modifiers

NeoForge provides a loot modifier out of the box for you to use:

### `neoforge:add_table`

This loot modifier rolls a second loot table and adds the results to the loot table the modifier is applied to.

```json5
{
    "type": "neoforge:add_table",
    "conditions": [], // the required loot conditions
    "priority": 1000, // the optional priority of execution
    "table": "minecraft:chests/abandoned_mineshaft" // the second table to roll
}
```

## Datagen

GLMs can be [datagenned][datagen]. This is done by subclassing `GlobalLootModifierProvider`:

```java
public class MyGlobalLootModifierProvider extends GlobalLootModifierProvider {
    // Get the parameters from the `GatherDataEvent`s.
    public MyGlobalLootModifierProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, ExampleMod.MOD_ID);
    }
    
    @Override
    protected void start() {
        // Call #add to add a new GLM. This also adds a corresponding entry in global_loot_modifiers.json.
        this.add(
                // The name of the modifier. This will be the file name.
                "my_loot_modifier_instance",
                // The loot modifier to add. For the sake of example, we add a weather loot condition.
                new MyLootModifier(new LootItemCondition[] {
                        WeatherCheck.weather().setRaining(true).build()
                }, 900, "somestring", 10, Items.DIRT),
                // A list of data load conditions. Note that these are unrelated to the loot conditions
                // specified on the modifier itself. For the sake of example, we add a mod loaded condition.
                // An overload of #add is available that accepts a vararg of conditions instead of a list.
                List.of(new ModLoadedCondition("create"))
        );
    }
}
```

And like all data providers, you must register the provider to the `GatherDataEvent`s:

```java
@SubscribeEvent // on the mod event bus
public static void onGatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createProvider(MyGlobalLootModifierProvider::new);
}
```

[codec]: ../../../datastorage/codecs.md
[datagen]: ../../index.md#data-generation
[loottable]: index.md
[loottableid]: lootconditions#neoforgeloot_table_id
[register]: ../../../concepts/registries.md#methods-for-registering
[identifier]: ../../../misc/identifier.md
[tags]: ../tags.md

## resources/server/loottables/index

# Loot Tables

Loot tables are data files that are used to define randomized loot drops. A loot table can be rolled, returning a (potentially empty) list of item stacks. The output of this process depends on (pseudo-)randomness. Loot tables are located at `data/<mod_id>/loot_table/<name>.json`. For example, the loot table `minecraft:blocks/dirt`, used by the dirt block, is located at `data/minecraft/loot_table/blocks/dirt.json`.

Minecraft uses loot tables at various points in the game, including [block] drops, [entity] drops, chest loot, fishing loot, and many others. How a loot table is referenced depends on the context:

- Every block will, by default, receive an associated loot table, located at `<block_namespace>:blocks/<block_name>`. This can be disabled by calling `#noLootTable` on the block's `Properties`, resulting in no loot table being created and the block dropping nothing; this is mainly done by air-like or technical blocks.
- Every entity that does not call `EntityType.Builder#noLootTable` (which is typically entities in `MobCategory#MISC`) will, by default, receive an associated loot table, located at `<entity_namespace>:entities/<entity_name>`. This can be changed by overriding `#getLootTable`. For example, sheep use this to roll different loot tables depending on their wool color.
- Chests in structures specify their loot table in their block entity data. Minecraft stores all chest loot tables in `minecraft:chests/<chest_name>`; it is recommended, but not required to follow this practice in mods.
- The loot tables for gift items that villagers may throw at players after a raid are defined in the [`neoforge:raid_hero_gifts` data map][raidherogifts].
- Other loot tables, for example the fishing loot table, are retrieved when needed from `level.getServer().reloadableRegistries().getLootTable(lootTableKey)`. A list of all vanilla loot table locations can be found in `BuiltInLootTables`.

:::warning
Loot tables should generally only be created for stuff that belongs to your mod. For modifying existing loot tables, [global loot modifiers (GLMs)][glm] should be used instead.
:::

Due to the complexity of the loot table system, loot tables are compromised of several sub-systems that each have a different purpose.

## Loot Entry

A loot entry (or loot pool entry), represented in code through the abstract `LootPoolEntryContainer` class, is a singular loot element. It can specify one or multiple items to be dropped.

Loot entries are generally split into two groups: singletons (with the common superclass `LootPoolSingletonContainer`) and composites (with the common superclass `CompositeEntryBase`), where composites are made up of multiple singletons. The following singleton types are provided by Minecraft:

- `minecraft:empty`: An empty loot entry, representing no item. Created in code by calling `EmptyLootItem#emptyItem`.
- `minecraft:item`: A singular loot item entry, dropping the specified item when rolled. Created in code by calling `LootItem#lootTableItem` with the desired item.
    - Setting stack size, data components, etc. can be done using loot functions.
- `minecraft:tag`: A tag entry, dropping all items in the specified tag when rolling. Has two variants, depending on the value of the boolean `expand` property. If `expand` is true, a separate entry for each item in the tag is generated, otherwise one entry is used to drop all items. Created by calling `TagEntry#tagContents` (for `expand=false`) or `TagEntry#expandTag` (for `expand=true`), each with an item [tag key][tags] parameter.
    - For example, if `expand` is true and the tag is `#minecraft:planks`, one entry is generated for each planks type (so 11 entries for the 11 vanilla planks + one entry per modded planks), each with the specified weight, quality and functions; whereas if `expand` is false, one single entry dropping all planks is used.
- `minecraft:slots`: A loot entry referencing any inventory slot (e.g., entities, items, etc.). The `minecraft:slot_range` source can be used to target entities and block entities, while `minecraft:contents` can be used to target items with content-based data components which have a defined and registered `ContainerComponentManipulator`.
- `minecraft:dynamic`: A loot entry referencing a dynamic drop. Dynamic drops are a system to add entries to a loot table that cannot be specified beforehand, instead adding them in code. A dynamic drops entry consists of an id and a `Consumer<ItemStack>` that actually adds the items. To add a dynamic drops entry, specify a `minecraft:dynamic` entry with the desired id and then add a corresponding consumer in the [loot context][context]. Created using `DynamicLoot#dynamicEntry`.
- `minecraft:loot_table`: A loot entry that rolls another loot table, adding the result of that loot table as a single entry. The other loot table can either be specified by id or be inlined as a whole. Created in code by calling `NestedLootTable#lootTableReference` with a `Identifier` parameter, or `NestedLootTable#inlineLootTable` with a `LootTable` object parameter for an inline loot table.

The following composite types are provided by Minecraft:

- `minecraft:group`: A loot entry containing a list of other loot entries, which are run in order. Created in code by calling `EntryGroup#list`, or by calling `#append` on another `LootPoolSingletonContainer.Builder`, each with other loot entry builders.
- `minecraft:sequence`: Like `minecraft:group`, but the loot entry stops running as soon as one sub-entry fails, discarding all entries after that. Created in code by calling `SequentialEntry#sequential`, or by calling `#then` on another `LootPoolSingletonContainer.Builder`, each with other loot entry builders.
- `minecraft:alternatives`: Sort of an opposite to `minecraft:sequence`, but the loot entry stops running as soon as one sub-entry succeeds (instead of as soon as one fails), discarding all entries after that. Created in code by calling `AlternativesEntry#alternatives`, or by calling `#otherwise` on another `LootPoolSingletonContainer.Builder`, each with other loot entry builders.

Through the common `LootPoolEntryContainer` superclass, all of them have `conditions` property, which provides a list of [loot conditions][lootcondition] to apply to this loot entry. If one condition fails, the entry is treated as if it weren't present.

For the singletons that extend `LootPoolSingletonContainer`, they additionally have:

- `weight`: The weight value. Defaults to 1. This is used for cases where some items should be more common than others. For example, given two loot entries, one with weight 3 and one with weight 1, then there is a 75% chance for the first entry to be chosen, and a 25% chance for the second entry.
- `quality`: The quality value. Defaults to 0. If this is non-zero, then this value is multiplied by the luck value (set in the [loot context][context]) and added to the weight when rolling the loot table.
- `functions`: A list of [loot functions][lootfunction] to apply to the outputs of this loot entry.

For modders, it is also possible to define [custom loot entry types][customentry].

## Loot Pool

A loot pool is, in essence, a list of loot entries. Loot tables can contain multiple loot pools, each loot pool will be rolled independently of the others.

Loot pools may contain the following contents:

- `entries`: A list of loot entries.
- `conditions`: A list of [loot conditions][lootcondition] to apply to this loot pool. If one condition fails, none of the loot pool's entries will be rolled.
- `functions`: A list of [loot functions][lootfunction] to apply to all loot entry outputs of this loot pool.
- `rolls` and `bonus_rolls`: Two number providers (read on) that together determine the amount of times this loot pool will be rolled. The formula is rolls + bonus_rolls * luck, where the luck value is set in the [loot parameters][parameters].
- `name`: A name for the loot pool. NeoForge-added. This can be used by [GLMs][glm]. If unspecified, this is the hash code of the loot pool, prefixed by `custom#`.

## Number Provider

Number providers are a way to get (pseudo-)randomized numbers in a datapack context. Primarily used by loot tables, they are also used in other contexts, for example in worldgen. Vanilla provides the following six number providers:

- `minecraft:constant`: A constant float value, rounding to integer where needed. Created through `ConstantValue#exactly`.
- `minecraft:uniform`: Uniformly-distributed random integer or float values, with min and max values set. All values between min and max have the same chance to appear. Created through `UniformGenerator#between`.
- `minecraft:binomial`: Binomially-distributed random integer values, with n and p values set. See [Binomial Distribution][binomial] for more information on what these values mean. Created through `BinomialDistributionGenerator#binomial`.
- `minecraft:score`: Given an entity target, a score name and (optionally) a scale value, retrieves the given scoreboard value for the entity target, multiplying it with the given scale value (if available). Created through `ScoreboardValue#fromScoreboard`.
- `minecraft:storage`: A value from the command storage at a given nbt path. Created through `new StorageValue`.
- `minecraft:sum`: Sums the values of other number providers together. Created through `new Sum`.
- `minecraft:enchantment_level`: A provider of values for each enchantment level. Created through `EnchantmentLevelProvider#forEnchantmentLevel`, providing a `LevelBasedValue`. Valid `LevelBasedValue`s are:
    - Simply a constant value, without a specified type. Created through `LevelBasedValue#constant`.
    - `minecraft:linear`: A linearly-increasing value per enchantment level, plus an optional constant base value. Created through `LevelBasedValue#perLevel`.
    - `minecraft:levels_squared`: Squares the enchantment value, and then adds an optional base value to it. Created through `new LevelBasedValue.LevelsSquared`.
    - `minecraft:fraction`: Accepts two other `LevelBasedValue`s, using them to create a fraction. Created through `new LevelBasedValue.Fraction`.
    - `minecraft:clamped`: Accepts another `LevelBasedValue`, alongside min and max values. Calculates the value using the other `LevelBasedValue` and clamps the result. Created through `new LevelBasedValue.Clamped`.
    - `minecraft:exponent`: Accepts two other `LevelBasedValue`s, raising the first value to the second value. Created through `new LevelBasedValue.Exponent`.
    - `minecraft:lookup`: Accepts a `List<Float>` and a fallback `LevelBasedValue`. Looks up the value to use in the list (level 1 is the first element in the list, level 2 is the second element, etc.), and uses the fallback value if the value for a level is missing. Created through `LevelBasedValue#lookup`.
- `minecraft:environment_attribute`: Gets the numeric value of an environment attribute at the current position or dimension. Created through `new EnvironmentAttributeValue`

Modders can also register [custom number providers][customnumber] and [custom level-based values][customlevelbased] if needed.

## Loot Parameters

A loot parameter, known internally as a `ContextKey<T>`, is a parameter provided to a loot table when rolled, where `T` is the type of the provided parameter, for example `BlockPos` or `Entity`. They can be used by [loot conditions][lootcondition] and [loot functions][lootfunction]. For example, the `minecraft:killed_by_player` loot condition checks for the presence of the `minecraft:player` parameter.

Minecraft provides the following loot parameters:

- `minecraft:this_entity`: An entity associated with the loot table, typically the killed entity. Access via `LootContextParams.THIS_ENTITY`.
- `minecraft:interacting_entity`: An entity that is interacting with the loot table, e.g. a player mining a block. Access via `LootContextParams.INTERACTING_ENTITY`.
- `minecraft:target_entity`: An entity associated with the loot table, typically the target of some interaction. Access via `LootContextParams.TARGET_ENTITY`.
- `minecraft:last_damage_player`: A player associated with the loot table, typically the player that last attacked the killed entity, even if the player kill was indirect (for example: the player tapped the entity, and it was then killed by spikes). Used e.g. for player-kill-only drops. Access via `LootContextParams.LAST_DAMAGE_PLAYER`.
- `minecraft:damage_source`: A [damage source][damagesource] associated with the loot table, typically the damage source that killed the entity. Access via `LootContextParams.DAMAGE_SOURCE`.
- `minecraft:attacking_entity`: An attacking entity associated with the loot table, typically the killer of the entity. Access via `LootContextParams.ATTACKING_ENTITY`.
- `minecraft:direct_attacking_entity`: A direct attacking entity associated with the loot table. For example, if the attacking entity were a skeleton, the direct attacking entity would be the arrow. Access via `LootContextParams.DIRECT_ATTACKING_ENTITY`.
- `minecraft:origin`: A location associated with the loot table, e.g. the location of a loot chest. Access via `LootContextParams.ORIGIN`.
- `minecraft:block_state`: A block state associated with the loot table, e.g. the broken block state. Access via `LootContextParams.BLOCK_STATE`.
- `minecraft:block_entity`: A block entity associated with the loot table, e.g. the block entity associated with the broken block. Used e.g. by shulker boxes to save their inventory to the dropped item. Access via `LootContextParams.BLOCK_ENTITY`.
- `minecraft:tool`: An item instance associated with the loot table, e.g. the item used to break a block. This is not necessarily a tool. Access via `LootContextParams.TOOL`.
- `minecraft:explosion_radius`: An explosion radius in the current context. Used primarily to apply explosion decay to drops. Access via `LootContextParams.EXPLOSION_RADIUS`.
- `minecraft:enchantment_level`: An enchantment level, used by enchantment logic. Access via `LootContextParams.ENCHANTMENT_LEVEL`.
- `minecraft:enchantment_active`: Whether the used item has an enchantment or not, used e.g. by silk touch checks. Access via `LootContextParams.ENCHANTMENT_ACTIVE`.
- `minecraft:additional_cost_component_allowed`: Allows a villager trade to incur an additional cost if desired by the trade metadata.

Custom loot parameters can be created by calling `new ContextKey<T>` with the desired id. Since they are merely resource location wrappers, they do not need to be registered.

### Entity Targets

Entity targets are a type used in loot conditions and functions, represented by the `LootContext.EntityTarget` enum in code. They are used to specify the entity loot parameter to query in a condition or function context. Valid values are:

- `"this"` or `LootContext.EntityTarget.THIS`: Represents the `"minecraft:this_entity"` parameter.
- `"attacker"` or `LootContext.EntityTarget.ATTACKER`: Represents the `"minecraft:attacking_entity"` parameter.
- `"direct_attacker"` or `LootContext.EntityTarget.DIRECT_ATTACKER`: Represents the `"minecraft:direct_attacking_entity"` parameter.
- `"attacking_player"` or `LootContext.EntityTarget.ATTACKING_PLAYER`: Represents the `"minecraft:last_damage_player"` parameter.
- `"target_entity"` or `LootContext.EntityTarget.TARGET_ENTITY`: Represents the `"minecraft:target_entity"` parameter.
- `"interacting_entity"` or `LootContext.EntityTarget.INTERACTING_ENTITY`: Represents the `"minecraft:interacting_entity"` parameter.

For example, the `minecraft:entity_properties` loot condition accepts an entity target to allow all four loot parameters to be checked, if that is what you (as the loot table author) need.

### Loot Parameter Sets

Loot parameter sets, also known as loot table types and known as `ContextKeySet`s in code, are a collection of required and optional loot parameters. Despite their name, they are not `Set`s (not even `Collection`s). Rather, they are a wrapper around two `Set<ContextKey<?>>`s, one holding the required parameters (`#required`) and one holding the optional parameters (`#allowed`). They are used to validate that users of loot parameters only use the parameters that can be expected to be available, and to verify that the required parameters are present when rolling a table. Besides that, they are also used in advancement and enchantment logic.

Vanilla provides the following loot parameter sets (required parameters are **bold**, optional parameters are _in italics_; the in-code names are constants in `LootContextParamSets`):

| ID                               | In-code name           | Specified Loot Parameters                                                                                                                                                                                                                                                                                            | Usage                                                     |
|----------------------------------|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| `minecraft:empty`                | `EMPTY`                | n/a                                                                                                                                                                                                                                                                                                                  | Fallback purposes.                                        |
| `minecraft:generic`              | `ALL_PARAMS`           | **`minecraft:origin`**, **`minecraft:tool`**, **`minecraft:block_state`**, **`minecraft:block_entity`**, **`minecraft:explosion_radius`**, **`minecraft:this_entity`**, **`minecraft:damage_source`**, **`minecraft:attacking_entity`**, **`minecraft:direct_attacking_entity`**, **`minecraft:last_damage_player`** | Validation.                                               |
| `minecraft:command`              | `COMMAND`              | **`minecraft:origin`**, _`minecraft:this_entity`_                                                                                                                                                                                                                                                                    | Commands.                                                 |
| `minecraft:selector`             | `SELECTOR`             | **`minecraft:origin`**, _`minecraft:this_entity`_                                                                                                                                                                                                                                                                    | Entity selectors in commands.                             |
| `minecraft:villager_trade`       | `VILLAGER_TRADE`       | **`minecraft:origin`**, **`minecraft:this_entity`**, **`minecraft:additional_cost_component_allowed`**,                                                                                                                                                                                                                                      | Villager Trades.                                        |
| `minecraft:block`                | `BLOCK`                | **`minecraft:origin`**, **`minecraft:tool`**, **`minecraft:block_state`**, _`minecraft:block_entity`_, _`minecraft:explosion_radius`_, _`minecraft:this_entity`_                                                                                                                                                     | Block breaking.                                           |
| `minecraft:block_use`            | `BLOCK_USE`            | **`minecraft:origin`**, **`minecraft:block_state`**, **`minecraft:this_entity`**                                                                                                                                                                                                                                     | No vanilla uses.                                          |
| `minecraft:block_interact`       | `BLOCK_INTERACT`       | **`minecraft:block_state`**, _`minecraft:block_entity`_, _`minecraft:interacting_entity`_, _`minecraft:tool`_                                                                                                                                                                 | Block interactions.                                         |
| `minecraft:hit_block`            | `HIT_BLOCK`            | **`minecraft:origin`**, **`minecraft:enchantment_level`**, **`minecraft:block_state`**, **`minecraft:this_entity`**                                                                                                                                                                                                  | The channeling enchantment.                               |
| `minecraft:chest`                | `CHEST`                | **`minecraft:origin`**, _`minecraft:this_entity`_, _`minecraft:attacking_entity`_                                                                                                                                                                                                                                    | Loot chests and similar containers, loot chest minecarts. |
| `minecraft:archaeology`          | `ARCHAEOLOGY`          | **`minecraft:origin`**, **`minecraft:this_entity`**, **`minecraft:tool`**                                                                                                                                                                                                                                                                    | Archaeology.                                              |
| `minecraft:vault`                | `VAULT`                | **`minecraft:origin`**, _`minecraft:this_entity`_, _`minecraft:tool`_                                                                                                                                                                                                                                                                    | Trial chamber vault rewards.                              |
| `minecraft:entity`               | `ENTITY`               | **`minecraft:origin`**, **`minecraft:this_entity`**, **`minecraft:damage_source`**, _`minecraft:attacking_entity`_, _`minecraft:direct_attacking_entity`_, _`minecraft:last_damage_player`_                                                                                                                          | Entity kills.                                             |
| `minecraft:entity_interact`      | `ENTITY_INTERACT`      | **`minecraft:target_entity`**, **`minecraft:tool`**, _`minecraft:interacting_entity`_ | Entity interactions.                                            |
| `minecraft:shearing`             | `SHEARING`             | **`minecraft:origin`**, **`minecraft:this_entity`**, **`minecraft:tool`**                                                                                                                                                                                                                                                                    | Shearing entities, e.g. sheep.                            |
| `minecraft:equipment`            | `EQUIPMENT`            | **`minecraft:origin`**, **`minecraft:this_entity`**                                                                                                                                                                                                                                                                  | Entity equipment for e.g. zombies.                        |
| `minecraft:gift`                 | `GIFT`                 | **`minecraft:origin`**, **`minecraft:this_entity`**                                                                                                                                                                                                                                                                  | Raid hero gifts.                                          |
| `minecraft:barter`               | `PIGLIN_BARTER`        | **`minecraft:this_entity`**                                                                                                                                                                                                                                                                                          | Piglin bartering.                                         |
| `minecraft:fishing`              | `FISHING`              | **`minecraft:origin`**, **`minecraft:tool`**, _`minecraft:this_entity`_, _`minecraft:attacking_entity`_                                                                                                                                                                                                              | Fishing.                                                  |
| `minecraft:enchanted_item`       | `ENCHANTED_ITEM`       | **`minecraft:tool`**, **`minecraft:enchantment_level`**                                                                                                                                                                                                                                                              | Several enchantments.                                     |
| `minecraft:enchanted_entity`     | `ENCHANTED_ENTITY`     | **`minecraft:origin`**, **`minecraft:enchantment_level`**, **`minecraft:this_entity`**                                                                                                                                                                                                                               | Several enchantments.                                     |
| `minecraft:enchanted_damage`     | `ENCHANTED_DAMAGE`     | **`minecraft:origin`**, **`minecraft:enchantment_level`**, **`minecraft:this_entity`**, **`minecraft:damage_source`**, _`minecraft:attacking_entity`_, _`minecraft:direct_attacking_entity`_                                                                                                                         | Damage and protection enchantments.                       |
| `minecraft:enchanted_location`   | `ENCHANTED_LOCATION`   | **`minecraft:origin`**, **`minecraft:enchantment_level`**, **`minecraft:enchantment_active`**, **`minecraft:this_entity`**                                                                                                                                                                                           | Frost walker and soul speed enchantments.                 |
| `minecraft:advancement_entity`   | `ADVANCEMENT_ENTITY`   | **`minecraft:origin`**, **`minecraft:this_entity`**                                                                                                                                                                                                                                                                  | Several [advancement criteria][advancement].              |
| `minecraft:advancement_location` | `ADVANCEMENT_LOCATION` | **`minecraft:origin`**, **`minecraft:tool`**, **`minecraft:block_state`**, **`minecraft:this_entity`**                                                                                                                                                                                                               | Several [advancement triggers][advancement].              |
| `minecraft:advancement_reward`   | `ADVANCEMENT_REWARD`   | **`minecraft:origin`**, **`minecraft:this_entity`**                                                                                                                                                                                                                                                                  | [Advancement rewards][advancement].                       |

### Loot Context

The loot context is an object containing situational information for rolling loot tables. The information includes:

- The `ServerLevel` the loot table is rolled in. Get via `#getLevel`.
- The `RandomSource` used to roll the loot table. Get via `#getRandom`.
- The loot parameters. Check presence using `#hasParameter`, and get single parameters using `#getParameter`.
- The luck value, used for calculating bonus rolls and quality values. Usually populated via the entity's luck attribute. Get via `#getLuck`.
- The dynamic drops consumers. See [above][entry] for more information. Set via `#addDynamicDrops`. No getter available.

## Loot Table

Combining all the previous elements, we finally get a loot table. Loot table JSONs can specify the following values:

- `pools`: A list of loot pools.
- `neoforge:conditions`: A list of [data load conditions][conditions]. **Warning: These are data load conditions, not [loot conditions][lootcondition]!**
- `functions`: A list of [loot functions][lootfunction] to apply to all loot entry outputs of this loot table.
- `type`: A loot parameter set, used to validate proper usage of loot parameters. Optional; if absent, validation will be skipped.
- `random_sequence`: A random sequence for this loot table, in the form of a resource location. Random sequences are provided by the `Level` and used for consistent loot table rolls under identical conditions. This commonly uses the loot table's location.

An example loot table could have the following format:

```json5
{
    "type": "chest", // loot parameter set
    "neoforge:conditions": [
        // data load conditions
    ],
    "functions": [
        // table-wide loot functions
    ],
    "pools": [ // list of loot pools
        {
            "rolls": 1, // amount of rolls of the loot table, using 5 here will yield 5 results from the pool
            "bonus_rolls": 0.5, // amount of bonus rolls
            "name": "my_pool",
            "conditions": [
                // pool-wide loot conditions
            ],
            "functions": [
                // pool-wide loot functions
            ],
            "entries": [ // list of loot table entries
                {
                    "type": "minecraft:item", // loot entry type
                    "name": "minecraft:dirt", // type-specific properties, for example the name of the item
                    "weight": 3, // weight of an entry
                    "quality": 1, // quality of an entry
                    "conditions": [
                        // entry-wide loot conditions
                    ],
                    "functions": [
                        // entry-wide loot functions
                    ]
                }
            ]
        }
    ]
}
```

## Rolling a Loot Table

To roll a loot table, we need two things: the loot table itself, and a loot context.

Let's start with getting the loot table itself. We can obtain a loot table using `level.getServer().reloadableRegistries().getLootTable(lootTableId)`. As the loot data is only available through the server, this logic must run on a [logical server][sides], not a logical client.

:::tip
Minecraft's built-in loot table IDs can be found in the `BuiltInLootTables` class. Block loot tables can be obtained through `BlockBehaviour#getLootTable`, and entity loot tables can be obtained through `EntityType#getDefaultLootTable` or `Entity#getLootTable`.
:::

Now that we have a loot table, let's build our parameter set. We begin by creating an instance of `LootParams.Builder`:

```java
// Make sure that you are on a server, otherwise the cast will fail.
LootParams.Builder builder = new LootParams.Builder((ServerLevel) level);
```

We can then add loot context parameters, like so:

```java
// Use whatever context parameters and values you need. Vanilla parameters can be found in LootContextParams.
builder.withParameter(LootContextParams.ORIGIN, position);
// This variant can accept null as the value, in which case an existing value for that parameter will be removed.
builder.withOptionalParameter(LootContextParams.ORIGIN, null);
// Add a dynamic drop.
builder.withDynamicDrop(Identifier.fromNamespaceAndPath("examplemod", "example_dynamic_drop"), stackAcceptor -> {
    // some logic here
});
// Set our luck value. Assumes that a player is available. Contexts without a player should use 0 here.
builder.withLuck(player.getLuck());
```

Finally, we can create the `LootParams` from the builder and use them to roll the loot table:

```java
// Specify a loot context param set here if you want.
LootParams params = builder.create(LootContextParamSets.EMPTY);
// Get the loot table.
LootTable table = level.getServer().reloadableRegistries().getLootTable(location);
// Actually roll the loot table.
List<ItemStack> list = table.getRandomItems(params);
// Use this instead if you are rolling the loot table for container contents, e.g. loot chests.
// This method takes care of properly splitting the loot items across the container.
List<ItemStack> containerList = table.fill(container, params, someSeed);
```

:::danger
`LootTable` additionally exposes a method named `#getRandomItemsRaw`. Unlike the various `#getRandomItems` variants, `#getRandomItemsRaw` method will not apply [global loot modifiers][glm]. Use this method only if you know what you are doing.
:::

## Datagen

Loot tables can be [datagenned][datagen] by registering a `LootTableProvider` and providing a list of `LootTableSubProvider` in the constructor:

```java
@SubscribeEvent // on the mod event bus
public static void onGatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createProvider((output, lookupProvider) -> new LootTableProvider(
        output,
        // A set of required table resource locations. These are later verified to be present.
        // It is generally not recommended for mods to validate existence,
        // therefore we pass in an empty set.
        Set.of(),
        // A list of sub provider entries. See below for what values to use here.
        List.of(...),
        // The registry access
        lookupProvider
    ));
}
```

### `LootTableSubProvider`s

`LootTableSubProvider`s are where the actual generation happens. To get started, we implement `LootTableSubProvider` and override `#generate`:

```java
public class MyLootTableSubProvider implements LootTableSubProvider {
    // The parameter is provided by the lambda (see below). It can be stored and used to lookup other registry entries.
    public MyLootTableSubProvider(HolderLookup.Provider lookupProvider) {
        // Store the lookupProvider in a field
    }

    @Override
    public void generate(BiConsumer<ResourceKey<LootTable>, LootTable.Builder> consumer) {
        // LootTable.lootTable() returns a loot table builder we can add loot tables to.
        consumer.accept(
                ResourceKey.create(
                    Registries.LOOT_TABLE,
                    Identifier.fromNamespaceAndPath(ExampleMod.MOD_ID, "example_loot_table")
                ),
                LootTable.lootTable()
                // Add a loot table-level loot function. This example uses a number provider (see below).
                .apply(SetItemCountFunction.setCount(ConstantValue.exactly(5)))
                // Add a loot pool.
                .withPool(LootPool.lootPool()
                        // Add a loot pool-level function, similar to above.
                        .apply(...)
                        // Add a loot pool-level condition. This example only rolls the pool if it is raining.
                        .when(WeatherCheck.weather().setRaining(true))
                        // Set the amount of rolls and bonus rolls, respectively.
                        // Both of these methods utilize a number provider.
                        .setRolls(UniformGenerator.between(5, 9))
                        .setBonusRolls(ConstantValue.exactly(1))
                        // Add a loot entry. This example returns an item loot entry. See below for more loot entries.
                        .add(LootItem.lootTableItem(Items.DIRT))
                )
        );
    }
}
```

Once we have our loot table sub provider, we add it to the constructor of our loot provider, like so:

```java
new LootTableProvider(output, Set.of(), List.of(
        new SubProviderEntry(
                // A reference to the sub provider's constructor.
                // This is a Function<HolderLookup.Provider, ? extends LootTableSubProvider>.
                MyLootTableSubProvider::new,
                // An associated loot context set. If you're unsure what to use, use empty.
                LootContextParamSets.EMPTY
        ),
        // other sub providers here (if applicable)
    ), lookupProvider
);
```

### `BlockLootSubProvider`

`BlockLootSubProvider` is an abstract helper class containing many helpers for creating common block loot tables, e.g. single item drops (`#createSingleItemTable`), dropping the block the table is created for (`#dropSelf`), silk touch-only drops (`#createSilkTouchOnlyTable`), drops for slab-like blocks (`#createSlabItemTable`), and many more. Unfortunately, setting up a `BlockLootSubProvider` for modded usage involves more boilerplate:

```java
public class MyBlockLootSubProvider extends BlockLootSubProvider {
    // The constructor can be private if this class is an inner class of your loot table provider.
    // The parameter is provided by the lambda in the LootTableProvider's constructor.
    public MyBlockLootSubProvider(HolderLookup.Provider lookupProvider) {
        // The first parameter is a set of blocks we are creating loot tables for. Instead of hardcoding,
        // we use our block registry and just pass an empty set here.
        // The second parameter is the feature flag set, this will be the default flags
        // unless you are adding custom flags (which is beyond the scope of this article).
        super(Set.of(), FeatureFlags.DEFAULT_FLAGS, lookupProvider);
    }

    // The contents of this Iterable are used for validation.
    // We return an Iterable over our block registry's values here.
    @Override
    protected Iterable<Block> getKnownBlocks() {
        // The contents of our DeferredRegister.
        return MyRegistries.BLOCK_REGISTRY.getEntries()
                .stream()
                // Cast to Block here, otherwise it will be a ? extends Block and Java will complain.
                .map(e -> (Block) e.value())
                .toList();
    }

    // Actually add our loot tables.
    @Override
    protected void generate() {
        // Equivalent to calling add(MyBlocks.EXAMPLE_BLOCK.get(), createSingleItemTable(MyBlocks.EXAMPLE_BLOCK.get()));
        this.dropSelf(MyBlocks.EXAMPLE_BLOCK.get());
        // Add a table with a silk touch only loot table.
        this.add(MyBlocks.EXAMPLE_SILK_TOUCHABLE_BLOCK.get(),
                this.createSilkTouchOnlyTable(MyBlocks.EXAMPLE_SILK_TOUCHABLE_BLOCK.get()));
        // other loot table additions here
    }
}
```

We then add our sub provider to the loot table provider's constructor like any other sub provider:

```java
new LootTableProvider(output, Set.of(), List.of(new SubProviderEntry(
        MyBlockLootTableSubProvider::new,
        LootContextParamSets.BLOCK // it makes sense to use BLOCK here
    )), lookupProvider
);
```

### `EntityLootSubProvider`

Similar to `BlockLootSubProvider`, `EntityLootSubProvider` provides many helpers for entity loot table generation. Also similar to `BlockLootSubProvider`, we must provide a `Stream<EntityType<?>>` of entities known to the provider (instead of the `Iterable<Block>` used before). Overall, our implementation looks very similar to our `BlockLootSubProvider`, but with every mentioned of blocks swapped out for entity types:

```java
public class MyEntityLootSubProvider extends EntityLootSubProvider {
    public MyEntityLootSubProvider(HolderLookup.Provider lookupProvider) {
        // Unlike with blocks, we do not provide a set of known entity types. Vanilla instead uses custom checks here.
        super(FeatureFlags.DEFAULT_FLAGS, lookupProvider);
    }

    // This class uses a Stream instead of an Iterable, so we need to adjust this slightly.
    @Override
    protected Stream<EntityType<?>> getKnownEntityTypes() {
        return MyRegistries.ENTITY_TYPES.getEntries()
                .stream()
                .map(e -> (EntityType<?>) e.value());
    }

    @Override
    protected void generate() {
        this.add(MyEntities.EXAMPLE_ENTITY.get(), LootTable.lootTable());
        // other loot table additions here
    }
}
```

And again, we then add our sub provider to the loot table provider's constructor:

```java
new LootTableProvider(output, Set.of(), List.of(new SubProviderEntry(
        MyEntityLootTableSubProvider::new,
        LootContextParamSets.ENTITY
    )), lookupProvider
);
```

[advancement]: ../advancements.md
[binomial]: https://en.wikipedia.org/wiki/Binomial_distribution
[block]: ../../../blocks/index.md
[conditions]: ../conditions.md
[context]: #loot-context
[customentry]: custom.md#custom-loot-entry-types
[customlevelbased]: custom.md#custom-level-based-values
[customnumber]: custom.md#custom-number-providers
[damagesource]: ../damagetypes.md#creating-and-using-damage-sources
[datagen]: ../../index.md#data-generation
[entity]: ../../../entities/index.md
[entry]: #loot-entry
[glm]: glm.md
[lootcondition]: lootconditions
[lootfunction]: lootfunctions
[parameters]: #loot-parameters
[raidherogifts]: ../datamaps/builtin.md#neoforgeraid_hero_gifts
[sides]: ../../../concepts/sides.md
[tags]: ../tags.md

## resources/server/loottables/lootconditions

# Loot Conditions

Loot conditions can be used to check whether a [loot entry][entry] or [loot pool][pool] should be used in the current context. In both cases, a list of conditions is defined; the entry or pool is only used if all conditions pass. During datagen, they are added to a `LootPoolEntryContainer.Builder<?>` or `LootPool.Builder` by calling `#when` with an instance of the desired condition. This article will outline the available loot conditions. To create your own loot conditions, see [Custom Loot Conditions][custom].

## `minecraft:inverted`

This condition accepts another condition and inverts its result. Requires whatever loot parameters the other condition requires.

```json5
{
    "condition": "minecraft:inverted",
    "term": {
        // Some other loot condition.
    }
}
```

During datagen, call `InvertedLootItemCondition#invert` with the condition to invert to construct a builder for this condition.

## `minecraft:all_of`

This condition accepts any number of other conditions and returns true if all sub conditions return true. If the list is empty, it returns false. Requires whatever loot parameters the other conditions require.

```json5
{
    "condition": "minecraft:all_of",
    "terms": [
        {
            // A loot condition.
        },
        {
            // Another loot condition.
        },
        {
            // Yet another loot condition.
        }
    ]
}
```

During datagen, call `AllOfCondition#allOf` with the desired condition(s) to construct a builder for this condition.

## `minecraft:any_of`

This condition accepts any number of other conditions and returns true if at least one sub condition returns true. If the list is empty, it returns false. Requires whatever loot parameters the other conditions require.

```json5
{
    "condition": "minecraft:any_of",
    "terms": [
        {
            // A loot condition.
        },
        {
            // Another loot condition.
        },
        {
            // Yet another loot condition.
        }
    ]
}
```

During datagen, call `AnyOfCondition#anyOf` with the desired condition(s) to construct a builder for this condition.

## `minecraft:random_chance`

This condition accepts a [number provider][numberprovider] representing a chance between 0 and 1, and randomly returns true or false depending on that chance. The number provider should generally not return values outside the `[0, 1]` interval.

```json5
{
    "condition": "minecraft:random_chance",
    // A constant 50% chance for the condition to apply. 
    "chance": 0.5
}
```

During datagen, call `LootItemRandomChanceCondition#randomChance` with the number provider or a (constant) float value to construct a builder for this condition.

## `minecraft:random_chance_with_enchanted_bonus`

This condition accepts an enchantment id, a [`LevelBasedValue`][numberprovider] and a constant fallback float value. If the specified enchantment is present, the `LevelBasedValue` is queried for a value. If the specified enchantment is absent, or no value could be retrieved from the `LevelBasedValue`, the constant fallback value is used. The condition then randomly returns true or false, with the previously determined value denoting the chance that true is returned. Requires the `minecraft:attacking_entity` parameter, falling back to level 0 if absent.

```json5
{
    "condition": "minecraft:random_chance_with_enchanted_bonus",
    // Add a 20% chance per looting level to succeed.
    "enchantment": "minecraft:looting",
    "enchanted_chance": {
        "type": "linear",
        "base": 0.2,
        "per_level_above_first": 0.2
    },
    // Always fail if the looting enchantment is not present.
    "unenchanted_chance": 0.0
}
```

During datagen, call `LootItemRandomChanceWithEnchantedBonusCondition#randomChanceAndLootingBoost` with the registry lookup (`HolderLookup.Provider`), the base value and the increase per level to construct a builder for this condition. Alternatively, call `new LootItemRandomChanceWithEnchantedBonusCondition` to further specify the values.

## `minecraft:value_check`

This condition accepts a [number provider][numberprovider] and an `IntRange`, returning true if the result of the number provided is within the range.

```json5
{
    "condition": "minecraft:value_check",
    // May be any number provider.
    "value": {
        "type": "minecraft:uniform",
        "min": 0.0,
        "max": 10.0
    },
    // A range with min/max values.
    "range": {
        "min": 2.0,
        "max": 5.0
    }
}
```

During datagen, call `ValueCheckCondition#hasValue` with the number provider and the range to construct a builder for this condition.

## `minecraft:time_check`

This condition checks if a given `WorldClock` is within an `IntRange`. Optionally, a `period` parameter can be provided to modulo the time with; this can be used to e.g. check the time of day for `minecraft:overworld` if `period` is 24000 (one in-game day/night cycle has 24000 ticks).

```json5
{
    "condition": "minecraft:time_check",
    // The clock instance to check the time of.
    // Points to a registered clock at `data/<namespace>/world_clock/<path>.json`.
    "clock": "minecraft:overworld",
    // Optional, can be omitted. If omitted, no modulo operation will take place.
    // We use 24000 here, which is the length of one in-game day/night cycle.
    "period": 24000,
    // A range with min/max values. This example checks if the time is between 0 and 12000.
    // Combined with the modulo operand of 24000 specified above, this example checks if it is currently daytime.
    "value": {
        "min": 0,
        "max": 12000
    }
}
```

During datagen, call `TimeCheck#time` with the clock and desired range to construct a builder for this condition. The `period` value can then be set on the builder using `#setPeriod`.

## `minecraft:weather_check`

This condition checks the current weather for raining and thundering.

```json5
{
    "condition": "minecraft:weather_check",
    // Optional. If unspecified, the rain state will not be checked.
    "raining": true,
    // Optional. If unspecified, the thundering state will not be checked.
    // Specifying "raining": true and "thundering": true is functionally equivalent to just specifying
    // "thundering": true, since it is always raining when a thunderstorm occurs.
    "thundering": false
}
```

During datagen, call `WeatherCheck#weather` to construct a builder for this condition. The `raining` and `thundering` values can then be set on the builder using `#setRaining` and `#setThundering`, respectively.

## `minecraft:location_check`

This condition accepts a `LocationPredicate` and an optional offset value for each axis direction. `LocationPredicate`s allow checking conditions such as the position itself, the block or fluid state at that position, the dimension, biome or structure at that position, the light level, whether the sky is visible, etc. All possible values can be viewed in the `LocationPredicate` class definition. Requires the `minecraft:origin` loot parameter, always failing if that parameter is absent.

```json5
{
    "condition": "minecraft:location_check",
    "predicate": {
        // Succeed if our target is anywhere in the nether.
        "dimension": "the_nether"
    },
    // Optional position offset values. Only relevant if you are checking the position in some way.
    // Must either be provided all at once, or not at all.
    "offsetX": 10,
    "offsetY": 10,
    "offsetZ": 10
}
```

During datagen, call `LocationCheck#checkLocation` with the `LocationPredicate` and optionally a `BlockPos` to construct a builder for this condition.

## `minecraft:block_state_property`

This condition checks for the specified block state properties to have the specified value in the broken block state. Requires the `minecraft:block_state` loot parameter, always failing if that parameter is absent.

```json5
{
    "condition": "minecraft:block_state_property",
    // The expected block. If this does not match the block that is actually broken, the condition fails.
    "block": "minecraft:oak_slab",
    // The block state properties to match. Unspecified properties can have either value.
    // In this example, we want to only succeed if a top slab - waterlogged or not - is broken.
    // If this specifies properties not present on the block, a log warning will be printed.
    "properties": {
        "type": "top"
    }
}
```

During datagen, call `LootItemBlockStatePropertyCondition#hasBlockStateProperties` with the block to construct a builder for this condition. The desired block state property values can then be set on the builder using `#setProperties`.

## `minecraft:survives_explosion`

This condition randomly destroys the drops. The chance for drops to survive is 1 / `explosion_radius` loot parameter. This function is used by all block drops, with very few exceptions such as the beacon or the dragon egg. Requires the `minecraft:explosion_radius` loot parameter, always succeeding if that parameter is absent.

```json5
{
    "condition": "minecraft:survives_explosion"
}
```

During datagen, call `ExplosionCondition#survivesExplosion` to construct a builder for this condition.

## `minecraft:match_tool`

This condition accepts an `ItemPredicate` that is checked against the `tool` loot parameter. An `ItemPredicate` can specify a list of valid item ids (`items`), a min/max range for the item count (`count`), a `DataComponentPredicate` (`components`) and a map of `ItemSubPredicate`s (`predicates`); all fields are optional. Requires the `minecraft:tool` loot parameter, always failing if that parameter is absent.

```json5
{
    "condition": "minecraft:match_tool",
    // Match a netherite pickaxe or axe.
    "predicate": {
        "items": [
            "minecraft:netherite_pickaxe",
            "minecraft:netherite_axe"
        ]
    }
}
```

During datagen, call `MatchTool#toolMatches` with an `ItemPredicate.Builder` to invert to construct a builder for this condition.

## `minecraft:enchantment_active`

This condition returns whether an enchantment is active or not. Requires the `minecraft:enchantment_active` loot parameter, always failing if that parameter is absent.

```json5
{
    "condition": "minecraft:enchantment_active",
    // Whether the enchantment should be active (true) or not (false).
    "active": true
}
```

During datagen, call `EnchantmentActiveCheck#enchantmentActiveCheck` or `#enchantmentInactiveCheck` to construct a builder for this condition.

## `minecraft:table_bonus`

This condition is similar to `minecraft:random_chance_with_enchanted_bonus`, but with fixed values instead of randomized values. Requires the `minecraft:tool` loot parameter, always failing if that parameter is absent.

```json5
{
    "condition": "minecraft:table_bonus",
    // Apply the bonus if the fortune enchantment is present.
    "enchantment": "minecraft:fortune",
    // The chances to use per level. This example has a 20% chance of succeeding if unenchanted,
    // 30% if enchanted at level 1, and 60% if enchanted at level 2 or above.
    "chances": [0.2, 0.3, 0.6]
}
```

During datagen, call `BonusLevelTableCondition#bonusLevelFlatChance` with the enchantment id and the chances to construct a builder for this condition.

## `minecraft:entity_properties`

This condition checks a given `EntityPredicate` against an [entity target][entitytarget]. The `EntityPredicate` can check the entity type, mob effects, nbt values, equipment, location, etc.

```json5
{
    "condition": "minecraft:entity_properties",
    // The entity target to use. Valid values are "this", "attacker", "direct_attacker" or "attacking_player".
    // These correspond to the "this_entity", "attacking_entity", "direct_attacking_entity" and
    // "last_damage_player" loot parameters, respectively.
    "entity": "attacker",
    // Only succeed if the target is a pig. The predicate may also be empty, this can be used
    // to check whether the specified entity target is set at all.
    "predicate": {
        "type": "minecraft:pig"
    }
}
```

During datagen, call `LootItemEntityPropertyCondition#entityPresent` with the entity target, or `LootItemEntityPropertyCondition#hasProperties` with the entity target and the `EntityPredicate`, to construct a builder for this condition.

## `minecraft:damage_source_properties`

This condition checks a given `DamageSourcePredicate` against the damage source loot parameter. Requires the `minecraft:origin` and `minecraft:damage_source` loot parameters, always failing if those parameter are absent.

```json5
{
    "condition": "minecraft:damage_source_properties",
    "predicate": {
        // Check whether the source entity is a zombie.
        "source_entity": {
            "type": "zombie"
        }
    }
}
```

During datagen, call `DamageSourceCondition#hasDamageSource` with a `DamageSourcePredicate.Builder` to construct a builder for this condition.

## `minecraft:killed_by_player`

This condition determines whether the kill was a player kill. Used by some entity drops, for example blaze rods dropped by blazes. Requires the `minecraft:last_player_damage` loot parameter, always failing if that parameter is absent.

```json5
{
    "condition": "minecraft:killed_by_player"
}
```

During datagen, call `LootItemKilledByPlayerCondition#killedByPlayer` to construct a builder for this condition.

## `minecraft:entity_scores`

This condition checks the [entity target][entitytarget]'s scoreboard. Requires the loot parameter corresponding to the specified entity target, always failing if that parameter is absent.

```json5
{
    "condition": "minecraft:entity_scores"
    // The entity target to use. Valid values are "this", "attacker", "direct_attacker" or "attacking_player".
    // These correspond to the "this_entity", "attacking_entity", "direct_attacking_entity" and
    // "last_damage_player" loot parameters, respectively.
    "entity": "attacker",
    // A list of scoreboard values that must be in the given ranges.
    "scores": {
        "score1": {
            "min": 0,
            "max": 100
        },
        "score2": {
            "min": 10,
            "max": 20
        }
    }
}
```

During datagen, call `EntityHasScoreCondition#hasScores` with an entity target to construct a builder for this condition. Then, add required scores to the builder using `#withScore`.

## `minecraft:reference`

This condition references a predicate file and returns its result. See [Item Predicates][predicate] for more information.

```json5
{
    "condition": "minecraft:reference",
    // Refers to the predicate file at data/examplemod/predicate/example_predicate.json.
    "name": "examplemod:example_predicate"
}
```

During datagen, call `ConditionReference#conditionReference` with the id of the referenced predicate file to construct a builder for this condition.

## `minecraft:environment_attribute_check`

This condition checks whether an environment attribute in the context dimension matches the given value. If the environment attribute is positional (it can change depending on a player's position in a level), it requires the `minecraft:origin` loot parameter, always failing if that parameter is absent. If the attribute is not present in the dimension, the value will be checked against the default value.

```json5
{
    "condition": "minecraft:environment_attribute_check",
    // The environment attribute to check the value of.
    "attribute": "minecraft:gameplay/water_evaporates",
    // The value the environment attribute must be.
    "value": false
}
```

During datagen, call `EnvironmentAttributeCheck#environmentAttribute` registered `EnvironmentAttribute` and its value to construct a builder for this condition.

## `neoforge:loot_table_id`

This condition only returns true if the surrounding loot table id matches. This is typically used within [global loot modifiers][glm].

```json5
{
    "condition": "neoforge:loot_table_id",
    // Will only apply when the loot table is for dirt
    "loot_table_id": "minecraft:blocks/dirt"
}
```

During datagen, call `LootTableIdCondition#builder` with the desired loot table id to construct a builder for this condition.

## `neoforge:can_item_perform_ability`

This condition only returns true if the item in the `tool` loot context parameter (`LootContextParams.TOOL`), usually the item used to break the block or kill the entity, can perform the specified [`ItemAbility`][itemability]. Requires the `minecraft:tool` loot parameter, always failing if that parameter is absent.

```json5
{
    "condition": "neoforge:can_item_perform_ability",
    // Will only apply if the tool can strip a log like an axe
    "ability": "axe_strip"
}
```

During datagen, call `CanItemPerformAbility#canItemPerformAbility` with the id of the desired item ability to construct a builder for this condition.

## See Also

- [Item Predicates][predicatejson] on the [Minecraft Wiki][mcwiki]

[custom]: custom.md#custom-loot-conditions
[entitytarget]: index.md#entity-targets
[entry]: index.md#loot-entry
[glm]: glm.md
[itemability]: ../../../items/tools.md#itemabilitys
[mcwiki]: https://minecraft.wiki
[numberprovider]: index.md#number-provider
[pool]: index.md#loot-pool
[predicate]: https://minecraft.wiki/w/Predicate
[predicatejson]: https://minecraft.wiki/w/Predicate#JSON_format
[registry]: ../../../concepts/registries.md

## resources/server/loottables/lootfunctions

# Loot Functions

Loot functions can be used to modify the result of a [loot entry][entry], or the multiple results of a [loot pool][pool] or [loot table][table]. In both cases, a list of functions is defined, which is run in order. During datagen, loot functions can be applied to `LootPoolSingletonContainer.Builder<?>`s, `LootPool.Builder`s and `LootTable.Builder`s by calling `#apply`. This article will outline the available loot functions. To create your own loot functions, see [Custom Loot Functions][custom].

:::note
Loot functions cannot be applied to composite loot entries (subclasses of `CompositeEntryBase` and their associated builder classes). They must be added to each singleton entry manually.
:::

All vanilla loot functions except `minecraft:sequence` can specify [loot conditions][conditions] in a `conditions` block. If one of these conditions fails, the function will not be applied. On the code side, this is controlled by the `LootItemConditionalFunction`, which all loot functions except for `SequenceFunction` extend.

## `minecraft:set_item`

Sets a different item to use in the result item stack.

```json5
{
    "function": "minecraft:set_item",
    // The item to use.
    "item": "minecraft:dirt"
}
```

It is currently not possible to create this function during datagen.

## `minecraft:set_count`

Sets an item count to use in the result item stack. Uses a [number provider][numberprovider].

```json5
{
    "function": "minecraft:set_count",
    // The count to use.
    "count": {
        "type": "minecraft:uniform",
        "min": 1,
        "max": 3
    },
    // Whether to add to the existing value instead of setting it. Optional, defaults to false.
    "add": true
}
```

During datagen, call `SetItemCountFunction#setCount` with the desired number provider and optionally an `add` boolean to construct a builder for this function.

## `minecraft:explosion_decay`

Applies an explosion decay. The item has a chance of 1 / `explosion_radius` to "survive". This is run multiple times depending on the count. Requires the `minecraft:explosion_radius` loot parameter, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:explosion_decay"
}
```

During datagen, call `ApplyExplosionDecay#explosionDecay` to construct a builder for this function.

## `minecraft:limit_count`

Clamps the count of the item stack between a given `IntRange`.

```json5
{
    "function": "minecraft:limit_count",
    // The limit to use. Can have a min, a max, or both.
    "limit": {
        "max": 32
    }
}
```

During datagen, call `LimitCount#limitCount` with the desired `IntRange` to construct a builder for this function.

## `minecraft:set_custom_data`

Sets custom NBT data on the item stack.

```json5
{
    "function": "minecraft:set_custom_data",
    "tag": {
        "exampleproperty": 0
    }
}
```

During datagen, call `SetCustomDataFunction#setCustomData` with the desired [`CompoundTag`][nbt] to construct a builder for this function.

:::warning
This function should generally be considered deprecated. Use `minecraft:set_components` instead.
:::

## `minecraft:copy_custom_data`

Copies custom NBT data from a block entity or entity source to the item stack. Use of this is discouraged for block entities, use `minecraft:copy_components` or `minecraft:set_contents` instead. For entities, this requires setting the [entity target][entitytarget]. Requires the loot parameter corresponding to the specified source (entity target or block entity), no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:copy_custom_data",
    // The source to use. Valid values are either an entity target, "block_entity" to use the loot context's
    // block entity parameter, or be "storage" for command storage. If this is "storage", it can instead be a
    // JSON object that additionally specify the command storage path to be used.
    "source": "this",
    // Example for using "storage".
    "source": {
        "type": "storage",
        "source": "examplepath"
    },
    // The copy operation(s).
    "ops": [
        {
            // The source and target paths. In this example, we copy from "src" in the source to "dest" in the target.
            "source": "src",
            "target": "dest",
            // A merging strategy. Valid values are "replace", "append", and "merge".
            "op": "merge"
        }
    ]
}
```

During datagen, call `CopyCustomDataFunction#copyData` with the associated `NbtProvider` to get the builder. Then call `Builder#copy` with the desired source and target values, as well as a merging strategy (optional, defaults to `replace`), to construct a builder for this function.

## `minecraft:set_components`

Sets [data component][datacomponent] values on the item stack. Most vanilla use cases have specialized functions that are explained below.

```json5
{
    "function": "minecraft:set_components",
    // Any component can be used. In this example, we set the dyed color of the item to red.
    "components": {
        "dyed_color": {
            "rgb": 16711680
        }
    }
}
```

During datagen, call `SetComponentsFunction#setComponent` with the desired data component and value to construct a builder for this function.

## `minecraft:copy_components`

Copies [data component][datacomponent] values from a block entity to the item stack. Requires one of the loot parameters from `LootContext.BlockEntityTarget`, `LootContext.EntityTarget`, or `LootContext.ItemStackTarget`.

```json5
{
    "function": "minecraft:copy_components",
    // One of the loot params specified in the context
    // For entities: 'this', 'attacker', 'direct_attacker', 'attacking_player', 'target_entity', 'interacting_entity'
    // For block entities: 'block_entity'
    // For item stacks: 'tool'
    "source": "block_entity",
    // By default, all components are copied. The "exclude" list allows excluding certain components, and the
    // "include" list allows explicitly re-including components. Both fields are optional.
    "exclude": [],
    "include": []
}
```

During datagen, call `CopyComponentsFunction#copyComponentsFromBlockEntity` for a block entity source or `copyComponentsFromEntity` for an entity source to construct a builder for this function. You can alternatively use `CopyComponentsFunction.ItemStackSource`, provided you [widen the access][at] of the builder constructor.

## `minecraft:copy_state`

Copies block state properties into the item stack's `block_state` [data component][datacomponent], used when trying to place a block. The block state properties to copy must be explicitly specified. Requires the `minecraft:block_state` loot parameter, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:copy_state",
    // The expected block. If this does not match the block that is actually broken, the function does not run.
    "block": "minecraft:oak_slab",
    // The block state properties to save.
    "properties": {
        "type": "top"
    }
}
```

During datagen, call `CopyBlockState#copyState` with the block to construct a builder for this condition. The desired block state property values can then be set on the builder using `#copy`.

## `minecraft:set_contents`

Sets contents of the item stack.

```json5
{
    "function": "minecraft:set_contents",
    // The contents component to use. Valid values are "container", "bundle_contents" and "charged_projectiles".
    "component": "container",
    // A list of loot entries to add to the contents.
    "entries": [
        {
            "type": "minecraft:empty",
            "weight": 3
        },
        {
            "type": "minecraft:item",
            "item": "minecraft:arrow"
        }
    ]
}
```

During datagen, call `SetContainerContents#setContents` with the desired contents component to construct a builder for this function. Then, call `#withEntry` on the builder to add entries.

## `minecraft:modify_contents`

Applies a function to the contents of the item stack.

```json5
{
    "function": "minecraft:modify_contents",
    // The contents component to use. Valid values are "container", "bundle_contents" and "charged_projectiles".
    "component": "container",
    // The function to use.
    "modifier": "apply_explosion_decay"
}
```

It is currently not possible to create this function during datagen.

## `minecraft:set_loot_table`

Sets a container loot table on the result item stack. Intended for chests and other loot containers that retain this property when placed down.

```json5
{
    "function": "minecraft:set_loot_table",
    // The id of the loot table to use.
    "name": "minecraft:entities/enderman",
    // The id of the block entity type of the target block entity.
    "type": "minecraft:chest",
    // The random seed for generating loot tables. Optional, defaults to 0.
    "seed": 42
}
```

During datagen, call `SetContainerLootTable#withLootTable` with the desired block entity type, loot table resource key and optionally a seed to construct a builder for this function.

## `minecraft:set_name`

Sets a name for the result item stack. The name can be a [`Component`][component] instead of a literal string. It can also be resolved from an [entity target][entitytarget]. Requires the corresponding entity loot parameter if applicable, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:set_name",
    "name": "Funny Item",
    // The entity target to use.
    "entity": "this",
    // Whether to set the custom name ("custom_name") or the item name ("item_name") itself.
    // Custom name are displayed in italics and can be changed in an anvil, while item names cannot.
    "target": "custom_name"
}
```

During datagen, call `SetNameFunction#setName` with the desired name component, the desired name target and optionally an entity target to construct a builder for this function.

## `minecraft:copy_name`

Copies an [entity target][entitytarget]'s or block entity's name into the result item stack. Requires the loot parameter corresponding to the specified source (entity target or block entity), no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:copy_name",
    // The entity target, or "block_entity" if a block entity's name should be copied.
    "source": "this"
}
```

During datagen, call `CopyNameFunction#copyName` with the desired context param for a `LootContext.BlockEntityTarget` or `LootContext.EntityTarget` to construct a builder for this function.

## `minecraft:set_lore`

Sets lore (tooltip lines) for the result item stack. The lines can be [`Component`][component]s instead of literal strings. It can also be resolved from an [entity target][entitytarget]. Requires the corresponding entity loot parameter if applicable, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:set_lore",
    "lore": [
        "Funny Lore",
        "Funny Lore 2"
    ],
    // The merging mode used. Valid values are:
    // - "append": Appends the entries to any existing lore entries.
    // - "insert": Inserts the entries at a certain position. The position is denoted as an additional field
    //   named "offset". "offset" is optional and defaults to 0.
    // - "replace_all": Removes all previous entries and then appends the entries.
    // - "replace_section": Removes a section of entries and then adds the entries at that position.
    //   The section removed is denoted through the "offset" and optional "size" fields.
    //   If "size" is omitted, the amount of lines in "lore" is used.
    "mode": {
        "type": "insert",
        "offset": 0
    },
    // The entity target to use.
    "entity": "this"
}
```

During datagen, call `SetLoreFunction#setLore` to construct a builder for this function. Then, call `#addLine`, `#setMode` and `#setResolutionContext` as needed on the builder.

## `minecraft:toggle_tooltips`

Enables or disables certain component tooltips.

```json5
{
    "function": "minecraft:toggle_tooltips",
    "toggles": {
        // All values are optional. If omitted, these values will use pre-existing values on the stack.
        // The pre-existing values are generally true, unless they have already been modified by another function.
        "minecraft:attribute_modifiers": false,
        "minecraft:can_break": false,
        "minecraft:can_place_on": false,
        "minecraft:dyed_color": false,
        "minecraft:enchantments": false,
        "minecraft:jukebox_playable": false,
        "minecraft:stored_enchantments": false,
        "minecraft:trim": false,
        "minecraft:unbreakable": false
    }
}
```

It is currently not possible to create this function during datagen.

## `minecraft:enchant_with_levels`

Randomly enchants the item stack with a given amount of levels. Uses a [number provider][numberprovider].

```json5
    {
    "function": "minecraft:enchant_with_levels",
    // The amount of levels to use.
    "levels": {
        "type": "minecraft:uniform",
        "min": 10,
        "max": 30
    },
    // A list of possible enchantments. Optional, defaults to all applicable enchantments for the item.
    "options": [
        "minecraft:sharpness",
        "minecraft:fire_aspect"
    ],
    // Whether to allow this function to apply an additional trade cost if successful.
    "include_additional_cost_component": true
}
```

During datagen, call `EnchantWithLevelsFunction#enchantWithLevels` with the desired number provider to construct a builder for this function. Then, if desired, set a list of enchantments on the builder using `#fromOptions`.

## `minecraft:enchant_randomly`

Enchants the item with one random enchantment.

```json5
{
    "function": "minecraft:enchant_randomly",
    // A list of possible enchantments. Optional, defaults to all enchantments.
    "options": [
        "minecraft:sharpness",
        "minecraft:fire_aspect"
    ],
    // Whether to only allow compatible enchantments, or any enchantments. Optional, defaults to true.
    "only_compatible": true,
    // Whether to allow this function to apply an additional trade cost if successful.
    "include_additional_cost_component": true
}
```

During datagen, call `EnchantRandomlyFunction#randomEnchantment` or `EnchantRandomlyFunction#randomApplicableEnchantment` to construct a builder for this function. Then, if desired, call `#withEnchantment`, `#withOneOf`, or `#withOptions` on the builder.

## `minecraft:set_enchantments`

Sets enchantments on the result item stack.

```json5
{
    "function": "minecraft:set_enchantments",
    // A map of enchantments to number providers.
    "enchantments": {
        "minecraft:fire_aspect": 2,
        "minecraft:sharpness": {
        "type": "minecraft:uniform",
        "min": 3,
        "max": 5,
        }
    },
    // Whether to add enchantment levels to existing levels instead of overwriting them. Optional, defaults to false.
    "add": true
}
```

During datagen, call `new SetEnchantmentsFunction.Builder` with the `add` boolean value (optionally) to construct a builder for this function. Then, call `#withEnchantment` to add an enchantment to set.

## `minecraft:enchanted_count_increase`

Increases the item stack count based on the enchantment value. Uses a [number provider][numberprovider]. Requires the `minecraft:attacking_entity` loot parameter, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:enchanted_count_increase",
    // The enchantment to use.
    "enchantment": "minecraft:fortune",
    // The increase count per level. The number provider is rolled once per function, not once per level.
    "count": {
        "type": "minecraft:uniform",
        "min": 1,
        "max": 3
    },
    // The stack size limit, which will not be exceeded no matter the enchantment level. Optional.
    "limit": 5
}
```

During datagen, call `EnchantedCountIncreaseFunction#lootingMultiplier` with the desired number provider to construct a builder for this function. Optionally, call `#setLimit` on the builder afterwards.

## `minecraft:apply_bonus`

Applies an increase to the item stack count based on the enchantment value and various formulas. Requires the `minecraft:tool` loot parameter, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:apply_bonus",
    // The enchantment value to query.
    "enchantment": "minecraft:fortune",
    // The formula to use. Valid values are:
    // - "minecraft:binomial_with_bonus_count": Applies a bonus based on a binomial distribution with
    //   n = enchantment level + extra and p = probability.
    // - "minecraft:ore_drops": Applies a bonus based on a special formula for ore drops, including randomness.
    // - "minecraft:uniform_bonus_count": Adds a bonus based on the enchantment level scaled by a constant multiplier.
    "formula": "ore_drops",
    // The parameter values, depending on the formula.
    // If the formula is "minecraft:binomial_with_bonus_count", requires "extra" and "probability".
    // If the formula is "minecraft:ore_drops", requires no parameters.
    // If the formula is "minecraft:uniform_bonus_count", requires "bonusMultiplier".
    "parameters": {}
}
```

During datagen, call `ApplyBonusCount#addBonusBinomialDistributionCount`, `ApplyBonusCount#addOreBonusCount` or `ApplyBonusCount#addUniformBonusCount` with the enchantment and other required parameters (depending on the formula) to construct a builder for this function.

## `minecraft:furnace_smelt`

Attempts to smelt the item as if it were in a furnace, returning the unmodified item stack if it could not be smelted.

```json5
{
    "function": "minecraft:furnace_smelt",
    // When true, will use the current input material to determine
    // the base count. Otherwise, will output one result.
    "use_input_count": true
}
```

During datagen, call `SmeltItemFunction#smelted` to construct a builder for this function.

## `minecraft:set_damage`

Sets a durability damage value on the result item stack. Uses a [number provider][numberprovider].

```json5
{
    "function": "minecraft:set_damage",
    // The damage to set.
    "damage": {
        "type": "minecraft:uniform",
        "min": 10,
        "max": 300
    },
    // Whether to add to the existing damage instead of setting it. Optional, defaults to false.
    "add": true
}
```

During datagen, call `SetItemDamageFunction#setDamage` with the desired number provider and optionally an `add` boolean to construct a builder for this function.

## `minecraft:set_attributes`

Adds a list of [attribute modifiers][attributemodifier] to the result item stack.

```json5
{
    "function": "minecraft:set_attributes",
    // A list of attribute modifiers.
    "modifiers": [
        {
            // The resource location id of the modifier. Should be prefixed by your mod id.
            "id": "examplemod:example_modifier",
            // The id of the attribute the modifier is for.
            "attribute": "minecraft:attack_damage",
            // The attribute modifier operation.
            // Valid values are "add_value", "add_multiplied_base" and "add_multiplied_total". 
            "operation": "add_value",
            // The amount of the modifier. This can also be a number provider.
            "amount": 5,
            // The slot(s) the modifier applies for. Valid values are "any" (any inventory slot),
            // "mainhand", "offhand", "hand", (mainhand/offhand/both hands),
            // "feet", "legs", "chest", "head", "armor" (boots/leggings/chestplates/helmets/any armor slots)
            // and "body" (horse armor and similar slots).
            "slot": "armor"
        }
    ],
    // Whether to replace the existing values instead of adding to them. Optional, defaults to true.
    "replace": false
}
```

During datagen, call `SetAttributesFunction#setAttributes` to construct a builder for this function. Then, add modifiers using `#withModifier` on the builder. Use `SetAttributesFunction#modifier` to get a modifier.

## `minecraft:set_potion`

Sets a potion on the result item stack.

```json5
{
    "function": "minecraft:set_potion",
    // The id of the potion.
    "id": "minecraft:strength"
}
```

During datagen, call `SetPotionFunction#setPotion` with the desired potion to construct a builder for this function.

## `minecraft:set_random_dyes`

Applies a random number of dyes to the result item stack, storing the resulting color in `DataComponents#DYED_COLOR`.

```json5
{
    "function": "minecraft:set_random_dyes",
    // A number provider of the number of dyes to apply to the result.
    "number_of_dyes": 3
}
```

During datagen, call `SetRandomDyesFunction#withCount` with the number of dyes to construct a builder for this function.

## `minecraft:set_random_potion`

Sets a random potion from the options available on the result item stack. If no options are available, picks any registered potion.

```json5
{
    "function": "minecraft:set_random_potion",
    // The potions to choose from.
    // Can either be a potion id, such as "minecraft:strength",
    // or a list of potion ids, such as ["minecraft:strength", "minecraft:night_vision", ...],
    // or a potion tag, such as "#minecraft:tradeable".
    "options": "minecraft:strength"
}
```

During datagen, call `SetRandomPotionFunction#fromTagKey` with optional `HolderSet` of potions to construct a builder for this function.

## `minecraft:set_stew_effect`

Sets a list of stew effects on the result item stack.

```json5
{
    "function": "minecraft:set_stew_effect",
    // The effects to set.
    "effects": [
        {
        // The effect id.
        "type": "minecraft:fire_resistance",
        // The effect duration, in ticks. This can also be a number provider.
        "duration": 100
        }
    ]
}
```

During datagen, call `SetStewEffectFunction#stewEffect` to construct a builder for this function. Then, call `#withModifier` on the builder.

## `minecraft:set_ominous_bottle_amplifier`

Sets an ominous bottle amplifier on the result item stack. Uses a [number provider][numberprovider].

```json5
{
    "function": "minecraft:set_ominous_bottle_amplifier",
    // The amplifier to use.
    "amplifier": {
        "type": "minecraft:uniform",
        "min": 1,
        "max": 3
    }
}
```

During datagen, call `SetOminousBottleAmplifierFunction#setAmplifier` with the desired number provider to construct a builder for this function.

## `minecraft:exploration_map`

Transforms the result item stack into an exploration map if and only if it is a map. Requires the `minecraft:origin` loot parameter, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:exploration_map",
    // A structure tag, containing the structures an exploration map can lead to.
    // Optional, defaults to "minecraft:on_treasure_maps", which only contains buried treasures by default.
    "destination": "minecraft:eye_of_ender_located",
    // The map decoration type to use. See the MapDecorationTypes class for available values.
    // Optional, defaults to "minecraft:mansion".
    "decoration": "minecraft:target_x",
    // The zoom level to use. Optional, defaults to 2.
    "zoom": 4,
    // The search radius to use. Optional, defaults to 50.
    "search_radius": 25,
    // Whether existing chunks are skipped when searching for structures. Optional, defaults to true.
    "skip_existing_chunks": true
}
```

During datagen, call `ExplorationMapFunction#makeExplorationMap` to construct a builder for this function. Then, call the various setters on the builder if desired.

## `minecraft:fill_player_head`

Sets the player head owner on the result item stack based on the given [entity target][entitytarget]. Requires the corresponding loot parameter, no modification is performed if that parameter is absent.

```json5
{
    "function": "minecraft:fill_player_head",
    // The entity target to use. If this doesn't resolve to a player, the stack is not modified.
    "entity": "this_entity"
}
```

During datagen, call `FillPlayerHead#fillPlayerHead` with the desired entity target to construct a builder for this function.

## `minecraft:set_banner_pattern`

Sets banner patterns on the result item stack. This is for banners, not banner pattern items.

```json5
{
    "function": "minecraft:set_banner_patterns",
    // A list of banner pattern layers.
    "patterns": [
        {
            // The id of the banner pattern to use.
            "pattern": "minecraft:globe",
            // The dye color of the layer.
            "color": "light_blue"
        }
    ],
    // Whether to append to the existing layers instead of replacing them.
    "append": true
}
```

During datagen, call `SetBannerPatternFunction#setBannerPattern` with the `append` boolean to construct a builder for this function. Then, call `#addPattern` to add patterns to the function.

## `minecraft:set_instrument`

Sets the instrument tag on the result item stack.

```json5
{
    "function": "minecraft:set_instrument",
    // The instrument tag to use.
    // Can either be an instrument id, such as "minecraft:admire_goat_horn",
    // or a list of instrument ids, such as ["minecraft:admire_goat_horn", "minecraft:seek_goat_horn", ...],
    // or a instrument tag, such as "#minecraft:goat_horns".
    "options": "#minecraft:goat_horns"
}
```

During datagen, call `SetInstrumentFunction#setInstrumentOptions` with a `HolderSet` of the instruments to construct a builder for this function.

## `minecraft:set_fireworks`

```json5
{
    "function": "minecraft:set_fireworks",
    // The explosions to use. Optional, uses the existing data component value if absent.
    "explosions": [
        {
            // The firework explosion shape to use. Valid vanilla values are "small_ball", "large_ball",
            // "star", "creeper" and "burst". Optional, defaults to "small_ball".
            "shape": "star",
            // The colors to use. Optional, defaults to an empty list.
            "colors": [
                16711680,
                65280
            ],
            // The fade colors to use. Optional, defaults to an empty list.
            "fade_colors": [
                65280,
                255
            ],
            // Whether the explosion has a trail. Optional, defaults to false.
            "has_trail": true,
            // Whether the explosion has a twinkle. Optional, defaults to false.
            "has_twinkle": true
        }
    ],
    // The flight duration of the fireworks. Optional, uses the existing data component value if absent.
    "flight_duration": 5
}
```

It is currently not possible to create this function during datagen.

## `minecraft:set_firework_explosion`

Sets a firework explosion on the result item stack.

```json5
{
    "function": "minecraft:set_firework_explosion",
    // The firework explosion shape to use. Valid vanilla values are "small_ball", "large_ball",
    // "star", "creeper" and "burst". Optional, defaults to "small_ball".
    "shape": "star",
    // The colors to use. Optional, defaults to an empty list.
    "colors": [
        16711680,
        65280
    ],
    // The fade colors to use. Optional, defaults to an empty list.
    "fade_colors": [
        65280,
        255
    ],
    // Whether the explosion has a trail. Optional, defaults to false.
    "trail": true,
    // Whether the explosion has a twinkle. Optional, defaults to false.
    "twinkle": true
}
```

It is currently not possible to create this function during datagen.

## `minecraft:set_book_cover`

Sets a written book's non-page-specific content.

```json5
{
    "function": "minecraft:set_book_cover",
    // The book title. Optional, if absent, the book title remains unchanged.
    "title": "Hello World!",
    // The book author. Optional, if absent, the book author remains unchanged.
    "author": "Steve",
    // The book generation, i.e. how often it has been copied. Clamped between 0 and 3.
    // Optional, if absent, the book generation remains unchanged.
    "generation": 2
}
```

During datagen, call `new SetBookCoverFunction` with the desired parameters to construct a builder for this function.

## `minecraft:set_written_book_pages`

Sets the pages of a written book.

```json5
{
    "function": "minecraft:set_written_book_pages",
    // The pages to set, as a list of strings.
    "pages": [
        "Hello World!",
        "Hello World on page 2!",
        "Never Gonna Give You Up!"
    ],
    // The merging mode used. Valid values are:
    // - "append": Appends the entries to any existing lore entries.
    // - "insert": Inserts the entries at a certain position. The position is denoted as an additional field
    //   named "offset". "offset" is optional and defaults to 0.
    // - "replace_all": Removes all previous entries and then appends the entries.
    // - "replace_section": Removes a section of entries and then adds the entries at that position.
    //   The section removed is denoted through the "offset" and optional "size" fields.
    //   If "size" is omitted, the amount of lines in "lore" is used.
    "mode": {
        "type": "insert",
        "offset": 0
    }
}
```

It is currently not possible to create this function during datagen.

## `minecraft:set_writable_book_pages`

Sets the pages of a writable book (book and quill).

```json5
{
    "function": "minecraft:set_writable_book_pages",
    // The pages to set, as a list of strings.
    "pages": [
        "Hello World!",
        "Hello World on page 2!",
        "Never Gonna Give You Up!"
    ],
    // The merging mode used. Valid values are:
    // - "append": Appends the entries to any existing lore entries.
    // - "insert": Inserts the entries at a certain position. The position is denoted as an additional field
    //   named "offset". "offset" is optional and defaults to 0.
    // - "replace_all": Removes all previous entries and then appends the entries.
    // - "replace_section": Removes a section of entries and then adds the entries at that position.
    //   The section removed is denoted through the "offset" and optional "size" fields.
    //   If "size" is omitted, the amount of lines in "lore" is used.
    "mode": {
        "type": "insert",
        "offset": 0
    }
}
```

It is currently not possible to create this function during datagen.

## `minecraft:set_custom_model_data`

Sets the custom model data of the resulting item stack to use during rendering.

```json5
{
    "function": "minecraft:set_custom_model_data",
    // The float used during item model selection for the specified index
    // for a client item with a `minecraft:custom_model_data` range property.
    "floats": [
        // Will select the model where the property is less than 0.5 when "index": 0
        0.5,
        // Will select the model where the property is less than 0.25 when "index": 1
        0.25
    ],
    // The boolean used during item model selection for the specified index
    // for a client item with a `minecraft:custom_model_data` condition property.
    "flags": [
        // Will select the model where the condition is true when "index": 0
        true,
        // Will select the model where the condition is false when "index": 1
        false
    ],
    // The string used during item model selection for the specified index
    // for a client item with a `minecraft:custom_model_data` select property.
    "strings": [
        // Will select the model with the "dummy" case when "index": 0
        "dummy",
        // Will select the model with the "example" case when "index": 1
        "example"
    ],
    // The tint color to use for the specified index for a client item
    // with a `minecraft:custom_model_data` tint source.
    // 0xFF000000 is ORed with this value for an opaque color.
    "colors": [
        // Blue when "index": 0
        255,
        // Green when "index": 1
        65280
    ]
}
```

During datagen, call `new SetCustomModelDataFunction()` with the list of conditions, optional number providers, booleans, strings, and number providers to construct the associated object.

## `minecraft:filtered`

This function accepts an `ItemPredicate` that is checked against the generated stack. Depending on if the check succeeds (`on_pass`) or fails (`on_fail`), the defined function is run. An `ItemPredicate` can specify a list of valid item ids (`items`), a min/max range for the item count (`count`), a `DataComponentPredicate` (`components`) and a map of `ItemSubPredicate`s (`predicates`); all fields are optional.

```json5
{
    "function": "minecraft:filtered",
    // The custom model data value to use. This can also be a number provider.
    "item_filter": {
        "items": [
            "minecraft:diamond_shovel"
        ]
    },
    // The loot function to run if the predicate succeeds.
    // A loot modifier file or an in-line list of functions.
    "on_pass": "examplemod:example_pass",
    // The loot function to run if the predicate fails.
    // A loot modifier file or an in-line list of functions.
    "on_fail": "examplemod:example_fail"
}
```

It is currently not possible to create this function during datagen.

:::warning
This function should generally be considered deprecated. Use the passed function with a `minecraft:match_tool` condition instead.
:::

## `minecraft:reference`

This function references an item modifier and applies it to the result item stack. See [Item Modifiers][itemmodifiers] for more information.

```json5
{
    "function": "minecraft:reference",
    // Refers to the item modifier file at data/examplemod/item_modifier/example_modifier.json.
    "name": "examplemod:example_modifier"
}
```

During datagen, call `FunctionReference#functionReference` with the id of the referenced predicate file to construct a builder for this function.

## `minecraft:sequence`

This function runs other loot functions one after another.

```json5
{
    "function": "minecraft:sequence",
    // A list of functions to run.
    "functions": [
        {
            "function": "minecraft:set_count",
            // ...
        },
        {
            "function": "minecraft:explosion_decay"
        }
    ],
}
```

During datagen, call `SequenceFunction#of` with the other functions to construct a builder for this function.

## `minecraft:discard`

This function discards the original stack, returning an empty item.

```json5
{
    "function": "minecraft:discard"
}
```

During datagen, call `DiscardItem#discardItem` with the other functions to construct a builder for this function.

## See Also

- [Item Modifiers][itemmodifiers] on the [Minecraft Wiki][mcwiki]

[at]: ../../../advanced/accesstransformers.md
[attributemodifier]: ../../../entities/attributes.md#attribute-modifiers
[component]: ../../client/i18n.md#components
[conditions]: lootconditions
[custom]: custom.md#custom-loot-functions
[datacomponent]: ../../../items/datacomponents.md
[entitytarget]: index.md#entity-targets
[entry]: index.md#loot-entry
[itemmodifiers]: https://minecraft.wiki/w/Item_modifier#JSON_format
[mcwiki]: https://minecraft.wiki
[nbt]: ../../../datastorage/nbt.md
[numberprovider]: index.md#number-provider
[pool]: index.md#loot-pool
[table]: index.md#loot-table

## resources/server/recipes/builtin

# Built-In Recipe Types

Minecraft provides a variety of recipe types and serializers out of the box for you to use. This article will explain each recipe type, as well as how to generate them.

## Crafting

Crafting recipes are typically made in crafting tables, crafters, or in modded crafting tables or machines. Their recipe type is `minecraft:crafting`.

### Shaped Crafting

Some of the most important recipes - such as the crafting table, sticks, or most tools - are created through shaped recipes. These recipes are defined by a crafting pattern or shape (hence "shaped") in which the items must be inserted. Let's have a look at what an example looks like:

```json5
{
    "type": "minecraft:crafting_shaped",
    "category": "equipment",
    "key": {
        "#": "minecraft:stick",
        "X": "minecraft:iron_ingot"
    },
    "pattern": [
        "XXX",
        " # ",
        " # "
    ],
    "result": {
        "count": 1,
        "id": "minecraft:iron_pickaxe"
    }
}
```

Let's digest this line for line:

- `type`: This is the id of the shaped recipe serializer, `minecraft:crafting_shaped`.
- `category`: This optional field defines the `CraftingBookCategory` in the crafting book.
- `key` and `pattern`: Together, these define how the items must be put into the crafting grid.
    - The pattern defines up to three lines of up to three-wide strings that define the shape. All lines must be the same length, i.e. the pattern must form a rectangular shape. Spaces can be used to denote slots that should stay empty.
    - The key associates the characters used in the pattern with [ingredients][ingredient]. In the above example, all `X`s in the pattern must be iron ingots, and all `#`s must be sticks.
- `result`: The result of the recipe. This is [an item stack template's JSON representation][itemjson].
- Not shown in the example is the `group` key. This optional string property creates a group in the recipe book. Recipes in the same group will be displayed as one in the recipe book.
- Not shown in the example is `show_notification`. This optional boolean, when false, disables the toast shown on the top right hand corner on first use or unlock.

And then, let's have a look at how you'd generate this recipe within `RecipeProvider#buildRecipes`:

```java
// We use a builder pattern, therefore no variable is created. Create a new builder by calling
// ShapedRecipeBuilder#shaped with the recipe category (found in the RecipeCategory enum)
// and a result item, a result item and count, or a result item stack template.
ShapedRecipeBuilder.shaped(this.registries.lookupOrThrow(Registries.ITEM), RecipeCategory.TOOLS, Items.IRON_PICKAXE)
        // Create the lines of your pattern. Each call to #pattern adds a new line.
        // Patterns will be validated, i.e. their shape will be checked.
        .pattern("XXX")
        .pattern(" # ")
        .pattern(" # ")
        // Create the keys for the pattern. All non-space characters used in the pattern must be defined.
        // This can either accept Ingredients, TagKey<Item>s or ItemLikes, i.e. items or blocks.
        .define('X', Items.IRON_INGOT)
        .define('#', Items.STICK)
        // Creates the recipe advancement. While not mandated by the consuming background systems,
        // the recipe builder will crash if you omit this. The first parameter is the advancement name,
        // and the second one is the condition. Normally, you want to use the has() shortcut for the condition.
        // Multiple advancement requirements can be added by calling #unlockedBy multiple times.
        .unlockedBy("has_iron_ingot", this.has(Items.IRON_INGOT))
        // Stores the recipe in the passed RecipeOutput, to be written to disk.
        // If you want to add conditions to the recipe, those can be set on the output.
        .save(this.output);
```

Additionally, you can call `#group` and `#showNotification` to set the recipe book group and toggle the toast pop-up, respectively.

### Shapeless Crafting

Unlike shaped crafting recipes, shapeless crafting recipes do not care about the order the ingredients are passed in. As such, there is no pattern and key, instead there is just a list of ingredients:

```json5
{
    "type": "minecraft:crafting_shapeless",
    "category": "misc",
    "ingredients": [
        "minecraft:brown_mushroom",
        "minecraft:red_mushroom",
        "minecraft:bowl"
    ],
    "result": {
        "count": 1,
        "id": "minecraft:mushroom_stew"
    }
}
```

Like before, let's digest this line for line:

- `type`: This is the id of the shapeless recipe serializer, `minecraft:crafting_shapeless`.
- `category`: This optional field defines the category in the crafting book.
- `ingredients`: A list of [ingredients][ingredient]. The list order is preserved in code for recipe viewing purposes, but the recipe itself accepts the ingredients in any order.
- `result`: The result of the recipe. This is [an item stack template's JSON representation][itemjson].
- Not shown in the example is the `group` key. This optional string property creates a group in the recipe book. Recipes in the same group will be displayed as one in the recipe book.

And then, let's have a look at how you'd generate this recipe in `RecipeProvider#buildRecipes`:

```java
// We use a builder pattern, therefore no variable is created. Create a new builder by calling
// ShapelessRecipeBuilder#shapeless with the recipe category (found in the RecipeCategory enum)
// and a result item, a result item and count, or a result item stack template.
ShapelessRecipeBuilder.shapeless(this.registries.lookupOrThrow(Registries.ITEM), RecipeCategory.MISC, Items.MUSHROOM_STEW)
        // Add the recipe ingredients. This can either accept Ingredients, TagKey<Item>s or ItemLikes.
        // Overloads also exist that additionally accept a count, adding the same ingredient multiple times.
        .requires(Blocks.BROWN_MUSHROOM)
        .requires(Blocks.RED_MUSHROOM)
        .requires(Items.BOWL)
        // Creates the recipe advancement. While not mandated by the consuming background systems,
        // the recipe builder will crash if you omit this. The first parameter is the advancement name,
        // and the second one is the condition. Normally, you want to use the has() shortcut for the condition.
        // Multiple advancement requirements can be added by calling #unlockedBy multiple times.
        .unlockedBy("has_mushroom_stew", this.has(Items.MUSHROOM_STEW))
        .unlockedBy("has_bowl", this.has(Items.BOWL))
        .unlockedBy("has_brown_mushroom", this.has(Blocks.BROWN_MUSHROOM))
        .unlockedBy("has_red_mushroom", this.has(Blocks.RED_MUSHROOM))
        // Stores the recipe in the passed RecipeOutput, to be written to disk.
        // If you want to add conditions to the recipe, those can be set on the output.
        .save(this.output);
```

Additionally, you can call `#group` to set the recipe book group.

:::info
One-item recipes (e.g. storage blocks unpacking) should be shapeless recipes to follow vanilla standards.
:::

### Imbuing Items

Imbue recipes are a special type of single item crafting recipes where the potion contents of the material stack are copied to the resulting stack. For example:

```json5
{
    "type": "minecraft:crafting_imbue",
    "category": "misc",
    "material": "minecraft:arrow",
    "result": {
        "count": 8,
        "id": "minecraft:tipped_arrow"
    },
    "source": "minecraft:lingering_potion"
}
```

Like before, let's digest this line for line:

- `type`: This is the id of the recipe serializer, `minecraft:crafting_imbue`.
- `category`: This optional field defines the category in the crafting book.
- `group`: This optional string property creates a group in the recipe book. Recipes in the same group will be displayed as one in the recipe book, which typically makes sense for transmuted recipes.
- `source`: The [ingredient] that contains the potion contents to imbue.
- `material`: The [ingredient] used to imbue the source.
- `result`: The result of the recipe. This is [an item stack template's JSON representation][itemjson].

And then, let's have a look at how you'd generate this recipe in `RecipeProvider#buildRecipes`:

```java
// We use a builder pattern, therefore no variable is created. Create a new builder by calling
// CustomCraftingRecipeBuilder#customCrafting with the recipe category (found in the RecipeCategory enum)
// and a factory function that takes in the `Recipe.CommonInfo` and `CraftingRecipe.CraftingBookInfo`
// to return the `Recipe` instance.
CustomCraftingRecipeBuilder.customCrafting(
    RecipeCategory.MISC,
    // The function used to construct the recipe instance.
    (commonInfo, bookInfo) -> new ImbueRecipe(
        commonInfo, bookInfo,
        // The source that contains the potion contents.
        Ingredient.of(Items.LINGERING_POTION),
        // The material used to imbue the source.
        Ingredient.of(Items.ARROW),
        // The resulting template with the potion contents.
        new ItemStackTemplate(Items.TIPPED_ARROW, 8)
    )
)
    // Creates the recipe advancement. While not mandated by the consuming background systems,
    // the recipe builder will crash if you omit this. The first parameter is the advancement name,
    // and the second one is the condition. Normally, you want to use the has() shortcut for the condition.
    // Multiple advancement requirements can be added by calling #unlockedBy multiple times.
    .unlockedBy("has_lingering_potion", this.has(Items.LINGERING_POTION))
    // Stores the recipe in the passed RecipeOutput, to be written to disk.
    // If you want to add conditions to the recipe, those can be set on the output.
    .save(this.output, "tipped_arrow");
```

Additionally, you can call `#group` to set the recipe book group.

### Transmute Crafting

Transmute recipes are a special type of single item crafting recipes where the input stack's data components are completely copied to the resulting stack. Transmutations usually occur between two different items where one is the dyed version of another. For example:

```json5
{
    "type": "minecraft:crafting_transmute",
    "category": "misc",
    "group": "shulker_box_dye",
    "input": "#minecraft:shulker_boxes",
    "material": "minecraft:blue_dye",
    "material_count": 1,
    "add_material_count_to_result": false,
    "result": {
        "id": "minecraft:blue_shulker_box"
    }
}
```

Like before, let's digest this line for line:

- `type`: This is the id of the recipe serializer, `minecraft:crafting_transmute`.
- `category`: This optional field defines the category in the crafting book.
- `group`: This optional string property creates a group in the recipe book. Recipes in the same group will be displayed as one in the recipe book, which typically makes sense for transmuted recipes.
- `input`: The [ingredient] to transmute.
- `material`: The [ingredient] that transforms the stack into its result.
- `material_count`: The number of `material`s required to transform the stack into its result.
- `result`: The result of the recipe. This is [an item stack template's JSON representation][itemjson].
- `add_material_count_to_result`: Whether the number of materials used should be added to the number of items returned as a result. This is commonly used for cloning item data, like maps.

And then, let's have a look at how you'd generate this recipe in `RecipeProvider#buildRecipes`:

```java
// We use a builder pattern, therefore no variable is created. Create a new builder by calling
// TransmuteRecipeBuilder#transmute with the recipe category (found in the RecipeCategory enum),
// the ingredient input, the ingredient material, and the resulting item.
TransmuteRecipeBuilder.transmute(RecipeCategory.MISC, this.tag(ItemTags.SHULKER_BOXES),
    Ingredient.of(DyeItem.byColor(DyeColor.BLUE)), ShulkerBoxBlock.getBlockByColor(DyeColor.BLUE).asItem())
        // Sets the group of the recipe to display in the recipe book.
        .group("shulker_box_dye")
        // Sets the number of materials required to transmute the stack.
        .setMaterialCount(TransmuteRecipe.DEFAULT_MATERIAL_COUNT)
        // Creates the recipe advancement. While not mandated by the consuming background systems,
        // the recipe builder will crash if you omit this. The first parameter is the advancement name,
        // and the second one is the condition. Normally, you want to use the has() shortcut for the condition.
        // Multiple advancement requirements can be added by calling #unlockedBy multiple times.
        .unlockedBy("has_shulker_box", this.has(ItemTags.SHULKER_BOXES))
        // Stores the recipe in the passed RecipeOutput, to be written to disk.
        // If you want to add conditions to the recipe, those can be set on the output.
        .save(this.output);
```

Additionally, `#addMaterialCountToOutput` can be used to add the number of materials used to the result count.

### Dying Items

Dye recipes are a special type of single item crafting recipes where the dye stacks' `DataComponents#DYE` are combined into a single `DataComponents#DYED_COLOR` and applied to the target stack to construct the result stack. All other components from the target stack are copied to the result stack, similar to [transmute crafting][transmute]. For example:

```json5
{
    "type": "minecraft:crafting_dye",
    "category": "misc",
    "dye": "#minecraft:dyes",
    "group": "dyed_armor",
    "result": {
        "id": "minecraft:leather_boots"
    },
    "target": "minecraft:leather_boots"
}
```

Like before, let's digest this line for line:

- `type`: This is the id of the recipe serializer, `minecraft:crafting_transmute`.
- `category`: This optional field defines the category in the crafting book.
- `group`: This optional string property creates a group in the recipe book. Recipes in the same group will be displayed as one in the recipe book, which typically makes sense for dying recipes.
- `target`: The [ingredient] to apply the dyes to.
- `dye`: The [ingredient] dyes that are used to dye the stack. They must all have the `DataComponents#DYE` [data component][datacomponent].
- `result`: The result of the recipe. This is [an item stack template's JSON representation][itemjson].

And then, let's have a look at how you'd generate this recipe in `RecipeProvider#buildRecipes`:

```java
// We use a builder pattern, therefore no variable is created. Create a new builder by calling
// CustomCraftingRecipeBuilder#customCrafting with the recipe category (found in the RecipeCategory enum)
// and a factory function that takes in the `Recipe.CommonInfo` and `CraftingRecipe.CraftingBookInfo`
// to return the `Recipe` instance.
CustomCraftingRecipeBuilder.customCrafting(
    RecipeCategory.MISC,
    // The function used to construct the recipe instance.
    (commonInfo, bookInfo) -> new DyeRecipe(
        commonInfo, bookInfo,
        // The target to apply the dyes to.
        Ingredient.of(Items.LEATHER_BOOTS),
        // The dyes that can be applied to the target.
        this.tag(ItemTags.DYES),
        // The resulting template with the applied dye color.
        new ItemStackTemplate(Items.LEATHER_BOOTS)
    )
)
    // Sets the group of the recipe to display in the recipe book.
    .group("dyed_armor")
    // Creates the recipe advancement. While not mandated by the consuming background systems,
    // the recipe builder will crash if you omit this. The first parameter is the advancement name,
    // and the second one is the condition. Normally, you want to use the has() shortcut for the condition.
    // Multiple advancement requirements can be added by calling #unlockedBy multiple times.
    .unlockedBy("has_leather_boots", this.has(Items.LEATHER_BOOTS))
    // Stores the recipe in the passed RecipeOutput, to be written to disk.
    // If you want to add conditions to the recipe, those can be set on the output.
    .save(this.output, "dyed_leather_boots");
```

### Special Recipes

There are many other crafting recipes that are specifically made for a single purpose (e.g. cloning books, creating fireworks) rather than be applied in a generic manner. Most of the time, this is to set data components on the output by calculating their values from the input stacks. These recipes are still configurable, usually taking in the input ingredients and the template result. For example:

```json5
{
    "type": "minecraft:crafting_special_firework_rocket",
    "fuel": "minecraft:gunpowder",
    "result": {
        "count": 3,
        "id": "minecraft:firework_rocket"
    },
    "shell": "minecraft:paper",
    "star": "minecraft:firework_star"
}
```

This recipe, which is for creating a firework rocket, specifies the fuel, shell, and star ingredients used to create the result. However, it assumes that the star ingredient has the `DataComponents#FIREWORK_EXPLOSION` component, as otherwise no explosion is added.

Minecraft prefixes most special crafting recipes with `crafting_special_`, however this practice is not necessary to follow.

Generating this recipe looks as follows in `RecipeProvider#buildRecipes`:

```java
// The parameter of #special is a Supplier<Recipe<?>>.
SpecialRecipeBuilder.special(
    () -> new FireworkRocketRecipe(
        Ingredient.of(Items.PAPER),
        Ingredient.of(Items.GUNPOWDER),
        Ingredient.of(Items.FIREWORK_STAR),
        new ItemStackTemplate(Items.FIREWORK_ROCKET, 3)
    )
)
    // This overload of #save allows us to specify a name. It can also be used on other recipe builders.
    .save(this.output, "firework_rocket");
```

Vanilla provides the following special crafting serializers (mods may add more):

- `minecraft:crafting_special_bannerduplicate`: For duplicating banners.
- `minecraft:crafting_special_bookcloning`: For copying written books. This increases the resulting book's generation property by one.
- `minecraft:crafting_special_firework_rocket`: For crafting firework rockets.
- `minecraft:crafting_special_firework_star`: For crafting firework stars.
- `minecraft:crafting_special_firework_star_fade`: For applying a fade to a firework star.
- `minecraft:crafting_special_mapextending`: For extending filled maps.
- `minecraft:crafting_special_repairitem`: For repairing two broken items into one.
- `minecraft:crafting_special_shielddecoration`: For applying a banner to a shield.
- `minecraft:crafting_decorated_pot`: For crafting decorated pots from sherds.

## Furnace-like Recipes

The second most important group of recipes are the ones made through smelting or a similar process. All recipes made in furnaces (type `minecraft:smelting`), smokers (`minecraft:smoking`), blast furnaces (`minecraft:blasting`) and campfires (`minecraft:campfire_cooking`) use the same format:

```json5
{
    "type": "minecraft:smelting",
    "category": "food",
    "cookingtime": 200,
    "experience": 0.1,
    "ingredient": {
        "item": "minecraft:kelp"
    },
    "result": {
        "id": "minecraft:dried_kelp"
    }
}
```

Let's digest this line by line:

- `type`: This is the id of the recipe serializer, `minecraft:smelting`. This may be different depending on what kind of furnace-like recipe you're making.
- `category`: This optional field defines the category in the cooking recipe book.
- `cookingtime`: This field determines how long the recipes needs to be processed, in ticks. All vanilla furnace recipes use 200, smokers and blast furnaces use 100, and campfires use 600. However, this can be any value you want.
- `experience`: Determines the amount of experience rewarded when making this recipe. This field is optional, and no experience will be awarded if it is omitted.
- `ingredient`: The input [ingredient] of the recipe.
- `result`: The result of the recipe. This is [an item stack template's JSON representation][itemjson].

Datagen for these recipes looks like this in `RecipeProvider#buildRecipes`:

```java
// Use #smoking for smoking recipes, #blasting for blasting recipes, and #campfireCooking for campfire recipes.
// All of these builders work the same otherwise.
SimpleCookingRecipeBuilder.smelting(
        // Our input ingredient.
        Ingredient.of(Items.KELP),
        // Our recipe category.
        RecipeCategory.FOOD,
        CookingBookCategory.FOOD
        // Our result item. May also be an ItemStackTemplate.
        Items.DRIED_KELP,
        // Our experience reward
        0.1f,
        // Our cooking time.
        200
)
        // The recipe advancement, like with the crafting recipes above.
        .unlockedBy("has_kelp", this.has(Blocks.KELP))
        // This overload of #save allows us to specify a name.
        .save(this.output, "dried_kelp_smelting");
```

:::info
The recipe type for these recipes is the same as their recipe serializer, i.e. furnaces use `minecraft:smelting`, smokers use `minecraft:smoking`, and so on.
:::

## Stonecutting

Stonecutter recipes use the `minecraft:stonecutting` recipe type. They are about as simple as it gets, with only a type, an input and an output:

```json5
{
    "type": "minecraft:stonecutting",
    "ingredient": "minecraft:andesite",
    "result": {
        "count": 2,
        "id": "minecraft:andesite_slab"
    }
}
```

The `type` defines the recipe serializer (`minecraft:stonecutting`). The ingredient is an [ingredient], and the result is a basic [item stack template JSON][itemjson]. Like crafting recipes, they can also optionally specify a `group` for grouping in the recipe book.

Datagen is also simple in `RecipeProvider#buildRecipes`:

```java
SingleItemRecipeBuilder.stonecutting(Ingredient.of(Items.ANDESITE), RecipeCategory.BUILDING_BLOCKS, Items.ANDESITE_SLAB, 2)
        .unlockedBy("has_andesite", this.has(Items.ANDESITE))
        .save(this.output, "andesite_slab_from_andesite_stonecutting");
```

Note that the single item recipe builder does not support actual ItemStack results, and as such, no results with data components. The recipe codec, however, does support them, so a custom builder would need to be implemented if this functionality was desired.

## Smithing

The smithing table supports two different recipe serializers. One is for transforming inputs into outputs, copying over the components of the input (such as enchantments), and the other is for applying components to the input. Both use the `minecraft:smithing` recipe type, and require three inputs, named the base, the template, and the addition item.

### Transform Smithing

This recipe serializer is for transforming two input items into one, preserving the data components of the first input. Vanilla uses this mainly for netherite equipment, however any items can be used here:

```json5
{
    "type": "minecraft:smithing_transform",
    "addition": "#minecraft:netherite_tool_materials",
    "base": "minecraft:diamond_axe",
    "result": {
        "id": "minecraft:netherite_axe"
    },
    "template": "minecraft:netherite_upgrade_smithing_template"
}
```

Let's break this down line by line:

- `type`: This is the id of the recipe serializer, `minecraft:smithing_transform`.
- `base`: The base [ingredient] of the recipe. Usually, this is some piece of equipment.
- `template`: The template [ingredient] of the recipe. Usually, this is a smithing template.
- `addition`: The addition [ingredient] of the recipe. Usually, this is some sort of material, for example a netherite ingot.
- `result`: The result of the recipe. This is [an item stack template's JSON representation][itemjson].

During datagen, call on `SmithingTransformRecipeBuilder#smithing` to add your recipe in `RecipeProvider#buildRecipes`:

```java
SmithingTransformRecipeBuilder.smithing(
        // The template ingredient.
        Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
        // The base ingredient.
        Ingredient.of(Items.DIAMOND_AXE),
        // The addition ingredient.
        this.tag(ItemTags.NETHERITE_TOOL_MATERIALS),
        // The recipe book category.
        RecipeCategory.TOOLS,
        // The result item. Note that while the recipe codec accepts an item stack template here, the builder does not.
        // If you need an item stack template output, you need to use your own builder.
        Items.NETHERITE_AXE
)
        // The recipe advancement, like with the other recipes above.
        .unlocks("has_netherite_ingot", this.has(ItemTags.NETHERITE_TOOL_MATERIALS))
        // This overload of #save allows us to specify a name.
        .save(this.output, "netherite_axe_smithing");
```

### Trim Smithing

Trim smithing is the process of applying armor trims to armor:

```json5
{
    "type": "minecraft:smithing_trim",
    "addition": "#minecraft:trim_materials",
    "base": "#minecraft:trimmable_armor",
    "pattern": "minecraft:spire",
    "template": "minecraft:bolt_armor_trim_smithing_template"
}
```

Again, let's break this down into its bits:

- `type`: This is the id of the recipe serializer, `minecraft:smithing_trim`.
- `base`: The base [ingredient] of the recipe. All vanilla use cases use the `minecraft:trimmable_armor` tag here.
- `template`: The template [ingredient] of the recipe. All vanilla use cases use a smithing trim template here.
- `addition`: The addition [ingredient] of the recipe. All vanilla use cases use the `minecraft:trim_materials` tag here.
- `pattern`: The trim pattern applied to the base ingredient.

This recipe serializer is notably missing a result field. This is because it uses the base input and "applies" the template and addition items on it, i.e., it sets the base's components based on the other inputs and uses the result of that operation as the recipe's result.

During datagen, call on `SmithingTrimRecipeBuilder#smithingTrim` to add your recipe in `RecipeProvider#buildRecipes`:

```java
SmithingTrimRecipeBuilder.smithingTrim(
        // The template ingredient.
        Ingredient.of(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE),
        // The base ingredient.
        this.tag(ItemTags.TRIMMABLE_ARMOR),
        // The addition ingredient.
        this.tag(ItemTags.TRIM_MATERIALS),
        // The trim pattern to apply to the base.
        this.registries.lookupOrThrow(Registries.TRIM_PATTERN).getOrThrow(TrimPatterns.SPIRE),
        // The recipe book category.
        RecipeCategory.MISC
)
        // The recipe advancement, like with the other recipes above.
        .unlocks("has_smithing_trim_template", this.has(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE))
        // This overload of #save allows us to specify a name. Yes, this name is copied from vanilla.
        .save(this.output, "bolt_armor_trim_smithing_template_smithing_trim");
```

[datacomponent]: ../../../items/datacomponents.md
[ingredient]: ingredients.md
[itemjson]: ../../../items/index.md#json-representation
[transmute]: #transmute-crafting

## resources/server/recipes/custom

# Custom Recipes

To add custom recipes, we need at least three things: a `Recipe`, a `RecipeType`, and a `RecipeSerializer`. Depending on what you are implementing, you may also need a custom `RecipeInput`, `RecipeDisplay`, `SlotDisplay`, `RecipeBookCategory`, and `RecipePropertySet` if reusing an existing subclass is not feasible.

For the sake of example, and to highlight many different features, we are going to implement a recipe-driven mechanic that requires you to right-click a `BlockState` in-world with a certain item, breaking the `BlockState` and dropping the result item.

## The Recipe Input

Let's begin by defining what we want to put into the recipe. It's important to understand that the recipe input represents the actual inputs that the player is using right now. As such, we don't use tags or ingredients here, instead we use the actual item stacks and blockstates we have available.

```java
// Our inputs are a BlockState and an ItemStack.
public record RightClickBlockInput(BlockState state, ItemStack stack) implements RecipeInput {
    // Method to get an item from a specific slot. We have one stack and no concept of slots, so we just assume
    // that slot 0 holds our item, and throw on any other slot. (Taken from SingleRecipeInput#getItem.)
    @Override
    public ItemStack getItem(int slot) {
        if (slot != 0) throw new IllegalArgumentException("No item for index " + slot);
        return this.stack();
    }

    // The slot size our input requires. Again, we don't really have a concept of slots, so we just return 1
    // because we have one item stack involved. Inputs with multiple items should return the actual count here.
    @Override
    public int size() {
        return 1;
    }
}
```

Recipe inputs don't need to be registered or serialized in any way because they are created on demand. It is not always necessary to create your own, the vanilla ones (`CraftingInput`, `SingleRecipeInput` and `SmithingRecipeInput`) are fine for many use cases.

## The Recipe Class

Now that we have our inputs, let's get to the recipe itself. This is what holds our recipe data, and also handles matching and returning the recipe result. As such, it is usually the longest class for your custom recipe.

```java
// The generic parameter for Recipe<T> is our RightClickBlockInput from above.
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    // An in-code representation of our recipe data. This can be basically anything you want.
    // Common things to have here is a processing time integer of some kind, or an experience reward.
    // Note that we now use an ingredient instead of an item stack for the input.
    private final Recipe.CommonInfo commonInfo;
    private final RightClickBlockRecipe.BlockBookInfo bookInfo;
    private final BlockState inputState;
    private final Ingredient inputItem;
    private final ItemStackTemplate result;

    // Add a constructor that sets all properties. 
    public RightClickBlockRecipe(Recipe.CommonInfo commonInfo, RightClickBlockRecipe.BlockBookInfo bookInfo, BlockState inputState, Ingredient inputItem, ItemStackTemplate result) {
        this.commonInfo = commonInfo;
        this.bookInfo = bookInfo;
        this.inputState = inputState;
        this.inputItem = inputItem;
        this.result = result;
    }

    // Check whether the given input matches this recipe. The first parameter matches the generic.
    // We check our blockstate and our item stack, and only return true if both match.
    // If we needed to check the dimensions of our input, we would also do so here.
    @Override
    public boolean matches(RightClickBlockInput input, Level level) {
        return this.inputState == input.state() && this.inputItem.test(input.stack());
    }

    // Return the result of the recipe here, based on the given input. The parameter matches the generic.
    // This can be created using `ItemStackTemplate#create`.
    @Override
    public ItemStack assemble(RightClickBlockInput input) {
        return this.result.create();
    }

    // When true, will prevent the recipe from being synced within the recipe book or awarded on use/unlock.
    // This should only be true if the recipe shouldn't appear in a recipe book, such as map extending.
    // Although this recipe takes in an input state, it could still be used in a custom recipe book using
    //   the methods below.
    @Override
    public boolean isSpecial() {
        return true;
    }

    // This example outlines the most important methods. There is a number of other methods to override.
    // Some methods will be explained in the below sections as they cannot be easily compressed and understood here.
    // Check the class definition of Recipe to view them all.
}
```

### Common Information

All recipes have common information which, although it may not be implemented the same way, is parsed from the JSON. For all recipes, vanilla provides `Recipe.CommonInfo`. Currently, it allows the recipe to specify whether to show the notification toast or not when unlocking. `CommonInfo` also provides a [map codec][codec] and [stream codec][streamcodec] for integrating with the [`RecipeSerializer`][serializer] below.

As such, the `CommonInfo` can then be used to specify some methods on the `Recipe`:

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    
    private final Recipe.CommonInfo commonInfo;
    // Other fields

    public RightClickBlockRecipe(Recipe.CommonInfo commonInfo, ...) {
        this.commonInfo = commonInfo;
        // Other initializations
    }

    @Override
    public boolean showNotification() {
        return this.commonInfo.showNotification();
    }

    // Other methods
}
```

:::note
You are not required to make use of the `CommonInfo` record, or even make the `show_notification` field available on the JSON. It is up to the modder to decide if it makes sense to use.
:::

## Recipe Book Information

Like the common information, there is also fields parsed from the JSON relating to the recipe book: a [GUI][gui] that displays recipes in some transformation menu (e.g., crafting table, furnace, etc.). For these fields, vanilla provides the `Recipe.BookInfo<CategoryType>` interface, where `CategoryType` defines either the category of the recipe (assuming it is serializable) or an intermediate serializable object that can be converted to the category. Like `CommonInfo`, it provides a [map codec][codec] and [stream codec][streamcodec] for integrating with the [`RecipeSerializer`][serializer] below.

For example:

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    
    private final RightClickBlockRecipe.BlockBookInfo bookInfo;
    // Other fields

    public RightClickBlockRecipe(RightClickBlockRecipe.BlockBookInfo bookInfo, ...) {
        this.bookInfo = bookInfo;
        // Other initializations
    }

    @Override
    public String group() {
        return this.bookInfo.group();
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        // Convert the serializable entry to its recipe book category.
        return switch (this.bookInfo.category()) {
            case BUILDING -> RecipeBookCategories.CRAFTING_BUILDING_BLOCKS;
            case EQUIPMENT -> RecipeBookCategories.CRAFTING_EQUIPMENT;
            case REDSTONE -> RecipeBookCategories.CRAFTING_REDSTONE;
            case MISC -> RecipeBookCategories.CRAFTING_MISC;
        };
    }

    // Other methods

    public record BlockBookInfo(CraftingBookCategory category, String group) implements Recipe.BookInfo<CraftingBookCategory> {
        public static final MapCodec<BlockBookInfo> MAP_CODEC = Recipe.BookInfo.mapCodec(
            // Takes in the codec for the generic, the default generic value, and the
            // constructor of `(category, group) -> bookInfo`.
            CraftingBookCategory.CODEC, CraftingBookCategory.MISC, BlockBookInfo::new
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, BlockBookInfo> STREAM_CODEC = Recipe.BookInfo.streamCodec(
            // Takes in the stream codec for the generic and the constructor of
            // `(category, group) -> bookInfo`.
            CraftingBookCategory.STREAM_CODEC, BlockBookInfo::new
        );
    }
}
```

:::note
Like with `CommonInfo`, you are not required to use `BookInfo` or even make fields available on the JSON. It is up to the modder to decide if it makes sense to use for their recipe (i.e., recipes where `Recipe#isSpecial` returns true do not appear in the recipe book, so `BookInfo` should not be used). However, both `Recipe#group` and `recipeBookCategory` must be non-null objects.
:::

### Recipe Groups

Groups act as a key to group recipes together into a single entry within the recipe book. If the group is set to an empty string, it will be treated as its own unique entry.

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    // other stuff here

    @Override
    public String group() {
        return this.bookInfo.group();
    }
}
```

### Book Categories

A `RecipeBookCategory` simply defines a group to display this recipe within in a recipe book. For example, an iron pickaxe crafting recipe would show up in the `RecipeBookCategories#CRAFTING_EQUIPMENT` while a cooked cod recipe would show up in `#FURNANCE_FOOD` or `#SMOKER_FOOD`. Each recipe has one associated `RecipeBookCategory`. The vanilla categories can be found in `RecipeBookCategories`.

:::note
There are two cooked cod recipes, one for the furnace and one for the smoker. The furnace and smoker recipes have different book categories.
:::

If your recipe does not fit into one of the existing categories, typically because the recipe does not use one of the existing crafting stations (e.g., crafting table, furnace), then a new `RecipeBookCategory` can be created. Each `RecipeBookCategory` must be [registered][registry] to `BuiltInRegistries#RECIPE_BOOK_CATEGORY`:

```java
/// For some DeferredRegister<RecipeBookCategory> RECIPE_BOOK_CATEGORIES
public static final Supplier<RecipeBookCategory> RIGHT_CLICK_BLOCK_CATEGORY = RECIPE_BOOK_CATEGORIES.register(
    "right_click_block", RecipeBookCategory::new
);
```

Then, to set the category, we can override `#recipeBookCategory` to either directly return our category, or use the book info (if implemented) to map to our category, like so:

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    // other stuff here

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return switch (this.bookInfo.category()) {
            case BUILDING -> RecipeBookCategories.CRAFTING_BUILDING_BLOCKS;
            case EQUIPMENT -> RecipeBookCategories.CRAFTING_EQUIPMENT;
            case REDSTONE -> RecipeBookCategories.CRAFTING_REDSTONE;
            case MISC -> RIGHT_CLICK_BLOCK_CATEGORY.get();
        };
    }
}
```

### Search Categories

All `RecipeBookCategory`s are technically `ExtendedRecipeBookCategory`s. There is another type of `ExtendedRecipeBookCategory` called `SearchRecipeBookCategory`, which is used to aggregate `RecipeBookCategory`s when viewing all recipes in a recipe book.

NeoForge allows users to specify their own `ExtendedRecipeBookCategory` as a search category via `RegisterRecipeBookSearchCategoriesEvent#register` on the mod event bus. `register` takes in the `ExtendedRecipeBookCategory` representing the search category and the `RecipeBookCategory`s that make up that search category. The `ExtendedRecipeBookCategory` search category does not need to be registered to some static vanilla registry.

```java
// In some location
public static final ExtendedRecipeBookCategory RIGHT_CLICK_BLOCK_SEARCH_CATEGORY = new ExtendedRecipeBookCategory() {};

@SubscribeEvent // on the mod event bus
public static void registerSearchCategories(RegisterRecipeBookSearchCategoriesEvent event) {
    event.register(
        // The search category
        RIGHT_CLICK_BLOCK_SEARCH_CATEGORY,
        // All recipe categories within the search category as varargs
        RecipeBookCategories.CRAFTING_BUILDING_BLOCKS,
        RecipeBookCategories.CRAFTING_EQUIPMENT,
        RecipeBookCategories.CRAFTING_REDSTONE,
        RIGHT_CLICK_BLOCK_CATEGORY.get()
    )
}
```

## Placement Info

A `PlacementInfo` is meant to define the crafting requirements used by the recipe consumer and whether/how it can be placed into its associated crafting station (e.g., crafting table, furnace). `PlacementInfo` are only meant for item ingredients, so if other types of ingredients are desired (e.g., fluid, block), the surrounding logic will need to be implemented from scratch. In these cases, the recipe can be labelled as not placeable, and say as such via `PlacementInfo#NOT_PLACEABLE`. However, if there is at least one item-like object in your recipe, you should create a `PlacementInfo`.

A `PlacementInfo` can be created via `create`, which takes in one or a list of ingredient, or `createFromOptionals`, which takes in a list of optional ingredients. If your recipe contains some representation of empty slots, then `createFromOptionals` should be used, providing an empty optional for an empty slot:

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    // other stuff here
    private PlacementInfo info;

    @Override
    public PlacementInfo placementInfo() {
        // This delegate is in case the ingredient is not fully populated at this point in time
        // Tags and recipes are loaded at the same time, which is why this might be the case.
        if (this.info == null) {
            // Use optional ingredient as the block state may have an item representation
            List<Optional<Ingredient>> ingredients = new ArrayList<>();
            Item stateItem = this.inputState.getBlock().asItem();
            ingredients.add(stateItem != Items.AIR ? Optional.of(Ingredient.of(stateItem)): Optional.empty());
            ingredients.add(Optional.of(this.inputItem));

            // Create placement info
            this.info = PlacementInfo.createFromOptionals(ingredients);
        }

        return this.info;
    }
}
```

## Slot Displays

`SlotDisplay`s represent the information on what should render in what slot when viewed by a recipe consumer, like a recipe book. A `SlotDisplay` has two methods. First there's `resolve`, which takes in the `ContextMap` containing the available registries and fuel values (as shown in `SlotDisplayContext`); and the current `DisplayContentsFactory`, which accepts the contents to display for this slot; and returns the transformed list of contents into the output to be accepted. Then there's `type`, which holds the [`MapCodec`][codec] and [`StreamCodec`][streamcodec] used to encode/decode the display.

`SlotDisplay`s are typically implemented on the [`Ingredient` via `#display`, or `ICustomIngredient#display` for modded ingredients][ingredients]; however, in some cases, the input may not be an ingredient, meaning a `SlotDisplay` will need to use one available, or have a new one created.

These are the available slot displays provided by Vanilla and NeoForge:

- `SlotDisplay.Empty`: A slot that represents nothing.
- `SlotDisplay.ItemSlotDisplay`: A slot that represents an item.
- `SlotDisplay.ItemStackSlotDisplay`: A slot that represents an item stack template.
- `SlotDisplay.TagSlotDisplay`: A slot that represents an item tag.
- `SlotDisplay.OnlyWithComponent`: A slot that filters some other display to only items with the given data component.
- `SlotDisplay.WithAnyPotion`: A slot that represents some input with a random `DataComponents#POTION_CONTENTS` value.
- `SlotDisplay.WithRemainder`: A slot that represents some input that has some crafting remainder.
- `SlotDisplay.AnyFuel`: A slot that represents all fuel items.
- `SlotDisplay.Composite`: A slot that represents a combination of other slot displays.
- `SlotDisplay.DyedSlotDemo`: A slot that represents a dye being applied to some target, setting `DataComponents#DYED_COLOR`. 
- `SlotDisplay.SmithingTrimDemoSlotDisplay`: A slot that represents a random smithing trim being applied to some base with the given material.
- `FluidSlotDisplay`: A slot that represents a fluid.
- `FluidStackSlotDisplay`: A slot that represents a fluid stack.
- `FluidTagSlotDisplay`: A slot that represents a fluid tag.

We have three 'slots' in our recipe: the `BlockState` input, the `Ingredient` input, and the `ItemStack` result. The `Ingredient` input will already have an associated `SlotDisplay` and the `ItemStack` can be represented by `SlotDisplay.ItemStackSlotDisplay`. The `BlockState`, on the other hand, will need its own custom `SlotDisplay` and `DisplayContentsFactory`, as existing ones only take in item stacks, and for this example, block states are handled in a different fashion.

Starting with the `DisplayContentsFactory`, it is meant to be a transformer for some type to desired content display type. The available factories are:

- `DisplayContentsFactory.ForStacks`: A transformer that takes in `ItemStack`s.
- `DisplayContentsFactory.ForRemainders`: A transformer that takes in the input object and a list of remainder objects.
- `ForFluidStacks`: A transformer that takes in a `FluidStack`.

With this, the `DisplayContentsFactory` can be implemented to transform the provided objects into the desired output. For example, `SlotDisplay.ItemStackContentsFactory`, takes the `ForStacks` transformer and has the stacks transformed into `ItemStack`s.

For our `BlockState`, we'll create a factory that takes in the state, along with a basic implementation that outputs the state itself.

```java
// A basic transformer for block states
public interface ForBlockStates<T> extends DisplayContentsFactory<T> {

    // Delegate methods
    default forState(Holder<Block> block) {
        return this.forState(block.value());
    }

    default forState(Block block) {
        return this.forState(block.defaultBlockState());
    }

    // The block state to take in and transform to the desired output
    T forState(BlockState state);
}

// An implementation for a block state output
public class BlockStateContentsFactory implements ForBlockStates<BlockState> {
    // Singleton instance
    public static final BlockStateContentsFactory INSTANCE = new BlockStateContentsFactory();

    private BlockStateContentsFactory() {}

    @Override
    public BlockState forState(BlockState state) {
        return state;
    }
}

// An implementation for an item stack output
public class BlockStateStackContentsFactory implements ForBlockStates<ItemStack> {
    // Singleton instance
    public static final BlockStateStackContentsFactory INSTANCE = new BlockStateStackContentsFactory();

    private BlockStateStackContentsFactory() {}

    @Override
    public ItemStack forState(BlockState state) {
        return new ItemStack(state.getBlock());
    }
}
```

Then, with that, we can create a new `SlotDisplay`. The `SlotDisplay.Type` must be [registered][registry]:

```java
// A simple slot display
public record BlockStateSlotDisplay(BlockState state) implements SlotDisplay {
    public static final MapCodec<BlockStateSlotDisplay> CODEC = BlockState.CODEC.fieldOf("state")
        .xmap(BlockStateSlotDisplay::new, BlockStateSlotDisplay::state);
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockStateSlotDisplay> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), BlockStateSlotDisplay::state,
            BlockStateSlotDisplay::new
        );
    
    @Override
    public <T> Stream<T> resolve(ContextMap context, DisplayContentsFactory<T> factory) {
        return switch (factory) {
            // Check for our contents factory and transform if necessary
            case ForBlockStates<T> states -> Stream.of(states.forState(this.state));
            // If you want the contents to be handled differently depending on contents display
            //   then you can case on other displays like so
            case ForStacks<T> stacks -> Stream.of(stacks.forStack(state.getBlock().asItem()));
            // If no factories match, then do not return anything in the transformed stream
            default -> Stream.empty();
        }
    }

    @Override
    public SlotDisplay.Type<? extends SlotDisplay> type() {
        // Return the registered type from below
        return BLOCK_STATE_SLOT_DISPLAY.get();
    }
}

// In some registrar class
/// For some DeferredRegister<SlotDisplay.Type<?>> SLOT_DISPLAY_TYPES
public static final Supplier<SlotDisplay.Type<BlockStateSlotDisplay>> BLOCK_STATE_SLOT_DISPLAY = SLOT_DISPLAY_TYPES.register(
    "block_state",
    () -> new SlotDisplay.Type<>(BlockStateSlotDisplay.CODEC, BlockStateSlotDisplay.STREAM_CODEC)
);
```

## Recipe Display

A `RecipeDisplay` is the same as a `SlotDisplay`, except that it represents an entire recipe. The default interface only keeps track of the `result` of recipe and the `craftingStation` which represents the workbench where the recipe is applied. The `RecipeDisplay` also has a `type` that holds the [`MapCodec`][codec] and [`StreamCodec`][streamcodec] used to encode/decode the display. However, no available subtypes of `RecipeDisplay` contain all the information required to properly render our recipe on the client. As such, we will need to create our own `RecipeDisplay`.

All slots and ingredients should be represented as `SlotDisplay`s. Any restrictions, such as grid size, can be provided in any manner the user decides.

```java
// A simple recipe display
public record RightClickBlockRecipeDisplay(
    SlotDisplay inputState,
    SlotDisplay inputItem,
    SlotDisplay result, // Implements RecipeDisplay#result
    SlotDisplay craftingStation // Implements RecipeDisplay#craftingStation
) implements RecipeDisplay {
    public static final MapCodec<RightClickBlockRecipeDisplay> MAP_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                    SlotDisplay.CODEC.fieldOf("input_state").forGetter(RightClickBlockRecipeDisplay::inputState),
                    SlotDisplay.CODEC.fieldOf("input_item").forGetter(RightClickBlockRecipeDisplay::inputItem),
                    SlotDisplay.CODEC.fieldOf("result").forGetter(RightClickBlockRecipeDisplay::result),
                    SlotDisplay.CODEC.fieldOf("crafting_station").forGetter(RightClickBlockRecipeDisplay::craftingStation)
                )
                .apply(instance, RightClickBlockRecipeDisplay::new)
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RightClickBlockRecipeDisplay> STREAM_CODEC = StreamCodec.composite(
        SlotDisplay.STREAM_CODEC,
        RightClickBlockRecipeDisplay::inputState,
        SlotDisplay.STREAM_CODEC,
        RightClickBlockRecipeDisplay::inputItem,
        SlotDisplay.STREAM_CODEC,
        RightClickBlockRecipeDisplay::result,
        SlotDisplay.STREAM_CODEC,
        RightClickBlockRecipeDisplay::craftingStation,
        RightClickBlockRecipeDisplay::new
    );

    @Override
    public RecipeDisplay.Type<? extends RecipeDisplay> type() {
        // Return the registered type from below
        return RIGHT_CLICK_BLOCK_RECIPE_DISPLAY.get();
    }
}

// In some registrar class
/// For some DeferredRegister<RecipeDisplay.Type<?>> RECIPE_DISPLAY_TYPES
public static final Supplier<RecipeDisplay.Type<RightClickBlockRecipeDisplay>> RIGHT_CLICK_BLOCK_RECIPE_DISPLAY = RECIPE_DISPLAY_TYPES.register(
    "right_click_block",
    () -> new RecipeDisplay.Type<>(RightClickBlockRecipeDisplay.CODEC, RightClickBlockRecipeDisplay.STREAM_CODEC)
);
```

Then we can create the recipe display for the recipe by overriding `#display` like so:

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    // other stuff here

    @Override
    public List<RecipeDisplay> display() {
        // You can have many different displays for the same recipe
        // But this example will only use one like the other recipes.
        return List.of(
            // Add our recipe display with the specified slots
            new RightClickBlockRecipeDisplay(
                new BlockStateSlotDisplay(this.inputState),
                this.inputItem.display(),
                new SlotDisplay.ItemStackSlotDisplay(this.result),
                new SlotDisplay.ItemSlotDisplay(Items.GRASS_BLOCK)
            )
        )
    }
}
```

## The Recipe Type

Next up, our recipe type. This is fairly straightforward because there's no data other than a name associated with a recipe type. They are one of two [registered][registry] parts of the recipe system, so like with all other registries, we create a `DeferredRegister` and register to it:

```java
public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
        DeferredRegister.create(Registries.RECIPE_TYPE, ExampleMod.MOD_ID);

public static final Supplier<RecipeType<RightClickBlockRecipe>> RIGHT_CLICK_BLOCK_TYPE =
        RECIPE_TYPES.register(
                "right_click_block",
                // Creates the recipe type, setting `toString` to the registry name of the type
                RecipeType::simple
        );
```

After we have registered our recipe type, we must override `#getType` in our recipe, like so:

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    // other stuff here

    @Override
    public RecipeType<? extends Recipe<RightClickBlockInput>> getType() {
        return RIGHT_CLICK_BLOCK_TYPE.get();
    }
}
```

## The Recipe Serializer

A recipe serializer provides two codecs, one map codec and one stream codec, for serialization from/to JSON and from/to network, respectively. This section will not go in depth about how the codecs work, please see [Map Codecs][codec] and [Stream Codecs][streamcodec] for more information.

We'll create a map codec and stream codec inside our recipe class.

```java
public static final MapCodec<RightClickBlockRecipe> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
        Recipe.CommonInfo.MAP_CODEC.forGetter(recipe -> recipe.commonInfo),
        RightClickBlockRecipe.BlockBookInfo.MAP_CODEC.forGetter(recipe -> recipe.bookInfo),
        BlockState.CODEC.fieldOf("state").forGetter(RightClickBlockRecipe::getInputState),
        Ingredient.CODEC.fieldOf("ingredient").forGetter(RightClickBlockRecipe::getInputItem),
        ItemStack.CODEC.fieldOf("result").forGetter(RightClickBlockRecipe::getResult)
).apply(inst, RightClickBlockRecipe::new));

public static final StreamCodec<RegistryFriendlyByteBuf, RightClickBlockRecipe> STREAM_CODEC = StreamCodec.composite(
        Recipe.CommonInfo.STREAM_CODEC, recipe -> recipe.commonInfo,
        RightClickBlockRecipe.BlockBookInfo.STREAM_CODEC, recipe -> recipe.bookInfo,
        ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), RightClickBlockRecipe::getInputState,
        Ingredient.CONTENTS_STREAM_CODEC, RightClickBlockRecipe::getInputItem,
        ItemStack.STREAM_CODEC, RightClickBlockRecipe::getResult,
        RightClickBlockRecipe::new
);
```

Like with the type, we'll create and register our serializer:

```java
public static final DeferredRegister<RecipeType<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, ExampleMod.MOD_ID);

public static final Supplier<RecipeSerializer<RightClickBlockRecipe>> RIGHT_CLICK_BLOCK =
        RECIPE_SERIALIZERS.register("right_click_block", ()-> new RecipeSerializer<>(RightClickBlockRecipe.CODEC, RightClickBlockRecipe.STREAM_CODEC));
```

And similarly, we must also override `#getSerializer` in our recipe, like so:

```java
public class RightClickBlockRecipe implements Recipe<RightClickBlockInput> {
    // other stuff here

    @Override
    public RecipeSerializer<? extends Recipe<RightClickBlockInput>> getSerializer() {
        return RIGHT_CLICK_BLOCK.get();
    }
}
```

## The Crafting Mechanic

Now that all parts of your recipe are complete, you can make yourself some recipe JSONs (see the [datagen] section for that) and then query the recipe manager for your recipes, like above. What you then do with the recipe is up to you. A common use case would be a machine that can process your recipes, storing the active recipe as a field.

In our case, however, we want to apply the recipe when an item is right-clicked on a block. We will do so using an [event handler][event]. Keep in mind that this is an example implementation, and you can alter this in any way you like (so long as you run it on the server). As we want the interaction state to match on both the client and server, we will also need to [sync any relevant input states across the network][networking].

We can set up a simple network implementation to sync the recipe inputs like so:

```java
// A basic packet class, must be registered.
public record ClientboundRightClickBlockRecipesPayload(
    Set<BlockState> inputStates, Set<Holder<Item>> inputItems
) implements CustomPacketPayload {

    // ...
}

// Packet stores data in an instance class.
// Present on both server and client to do initial matching.
public interface RightClickBlockRecipeInputs {

    Set<BlockState> inputStates();
    Set<Holder<Item>> inputItems();

    default boolean test(BlockState state, ItemStack stack) {
        return this.inputStates().contains(state) && this.inputItems().contains(stack.getItemHolder());
    }
}

// Server resource listener so it can be reloaded when recipes are.
public class ServerRightClickBlockRecipeInputs implements ResourceManagerReloadListener, RightClickBlockRecipeInputs {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("examplemod", "block_recipe_inputs");

    private final RecipeManager recipeManager;

    private Set<BlockState> inputStates;
    private Set<Holder<Item>> inputItems;

    public RightClickBlockRecipeInputs(RecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    // Set inputs here as #apply is fired synchronously based on listener registration order.
    // Recipes are always applied first.
    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return; // Should never be null

        // Populate inputs
        Set<BlockState> inputStates = new HashSet<>();
        Set<Holder<Item>> inputItems = new HashSet<>();

        this.recipeManager.recipeMap().byType(RIGHT_CLICK_BLOCK_TYPE.get())
            .forEach(holder -> {
                var recipe = holder.value();
                inputStates.add(recipe.getInputState());
                inputItems.addAll(recipe.getInputItem().items());
            });
        
        this.inputStates = Set.copyOf(inputStates);
        this.inputItems = Set.copyOf(inputItems);
    }

    public void syncToClient(Stream<ServerPlayer> players) {
        ClientboundRightClickBlockRecipesPayload payload =
            new ClientboundRightClickBlockRecipesPayload(this.inputStates, this.inputItems);
        players.forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    @Override
    public Set<BlockState> inputStates() {
        return this.inputStates;
    }

    @Override
    public Set<Holder<Item>> inputItems() {
        return this.inputItems;
    }
}

// Client implementation to hold the inputs.
public record ClientRightClickBlockRecipeInputs(
    Set<BlockState> inputStates, Set<Holder<Item>> inputItems
) implements RightClickBlockRecipeInputs {

    public ClientRightClickBlockRecipeInputs(Set<BlockState> inputStates, Set<Holder<Item>> inputItems) {
        this.inputStates = Set.copyOf(inputStates);
        this.inputItems = Set.copyOf(inputItems);
    }
}

// Handling the recipe instance depending on side.
public class ServerRightClickBlockRecipes {

    private static ServerRightClickBlockRecipeInputs inputs;

    public static RightClickBlockRecipeInputs inputs() {
        return ServerRightClickBlockRecipes.inputs;
    }

    @SubscribeEvent // on the game event bus
    public static void addListener(AddServerReloadListenersEvent event) {
        // Register server reload listener
        ServerRightClickBlockRecipes.inputs = new ServerRightClickBlockRecipeInputs(
            event.getServerResources().getRecipeManager()
        );
        event.addListener(ServerRightClickBlockRecipeInputs.ID, ServerRightClickBlockRecipes.inputs);
        // Make sure it runs after recipes
        event.addDependency(VanillaServerListeners.RECIPES, ServerRightClickBlockRecipeInputs.ID);
    }

    @SubscribeEvent // on the game event bus
    public static void datapackSync(OnDatapackSyncEvent event) {
        // Send to client
        ServerRightClickBlockRecipes.inputs.syncToClient(event.getRelevantPlayers());
    }
}

public class ClientRightClickBlockRecipes {

    private static ClientRightClickBlockRecipeInputs inputs;

    public static RightClickBlockRecipeInputs inputs() {
        return ClientRightClickBlockRecipes.inputs;
    }

    // Handling the sent packet
    public static void handle(final ClientboundRightClickBlockRecipesPayload data, final IPayloadContext context) {
        // Do something with the data, on the main thread
        ClientRightClickBlockRecipes.inputs = new ClientRightClickBlockRecipeInputs(
            data.inputStates(), data.inputItems()
        );
    }

    @SubscribeEvent // on the game event bus only on the physical client
    public static void clientLogOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Clear the stored inputs on world log out
        ClientRightClickBlockRecipes.inputs = null;
    }
}

public class RightClickBlockRecipes {
    // Make proxy method to access properly
    public static RightClickBlockRecipeInputs inputs(Level level) {
        return level.isClientSide()
            ? ClientRightClickBlockRecipes.inputs()
            : ServerRightClickBlockRecipes.inputs();
    }
}
```

Alternatively, you can sync the [full recipe to the client instead][clientrecipes]:

```java
// Present on both server and client to do initial matching.
public interface RightClickBlockRecipeInputs {

    Set<BlockState> inputStates();
    Set<Holder<Item>> inputItems();

    default boolean test(BlockState state, ItemStack stack) {
        return this.inputStates().contains(state) && this.inputItems().contains(stack.getItemHolder());
    }
}

// Server resource listener so it can be reloaded when recipes are.
public class ServerRightClickBlockRecipeInputs implements ResourceManagerReloadListener, RightClickBlockRecipeInputs {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("examplemod", "block_recipe_inputs");

    private final RecipeManager recipeManager;

    private Set<BlockState> inputStates;
    private Set<Holder<Item>> inputItems;

    public RightClickBlockRecipeInputs(RecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    // Set inputs here as #apply is fired synchronously based on listener registration order.
    // Recipes are always applied first.
    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) { // Should never be null
            // Populate inputs
            Set<BlockState> inputStates = new HashSet<>();
            Set<Holder<Item>> inputItems = new HashSet<>();

            this.recipeManager.recipeMap().byType(RIGHT_CLICK_BLOCK_TYPE.get())
                .forEach(holder -> {
                    var recipe = holder.value();
                    inputStates.add(recipe.getInputState());
                    inputItems.addAll(recipe.getInputItem().items());
                });
            
            this.inputStates = Set.copyOf(inputStates);
            this.inputItems = Set.copyOf(inputItems);
        }
    }

    @Override
    public Set<BlockState> inputStates() {
        return this.inputStates;
    }

    @Override
    public Set<Holder<Item>> inputItems() {
        return this.inputItems;
    }
}

// Client implementation to hold the inputs.
public record ClientRightClickBlockRecipeInputs(
    Set<BlockState> inputStates, Set<Holder<Item>> inputItems
) implements RightClickBlockRecipeInputs {

    public ClientRightClickBlockRecipeInputs(Set<BlockState> inputStates, Set<Holder<Item>> inputItems) {
        this.inputStates = Set.copyOf(inputStates);
        this.inputItems = Set.copyOf(inputItems);
    }
}

// Handling the recipe instance depending on side.
public class ServerRightClickBlockRecipes {

    private static ServerRightClickBlockRecipeInputs inputs;

    public static RightClickBlockRecipeInputs inputs() {
        return ServerRightClickBlockRecipes.inputs;
    }

    @SubscribeEvent // on the game event bus
    public static void addListener(AddServerReloadListenersEvent event) {
        // Register server reload listener
        ServerRightClickBlockRecipes.inputs = new ServerRightClickBlockRecipeInputs(
            event.getServerResources().getRecipeManager()
        );
        event.addListener(ServerRightClickBlockRecipeInputs.ID, ServerRightClickBlockRecipes.inputs);
        // Make sure it runs after recipes
        event.addDependency(VanillaServerListeners.RECIPES, ServerRightClickBlockRecipeInputs.ID);
    }

    @SubscribeEvent // on the game event bus
    public static void datapackSync(OnDatapackSyncEvent event) {
        // Specify what recipe types to sync to the client
        event.sendRecipes(RIGHT_CLICK_BLOCK_TYPE.get());
    }
}

public class ClientRightClickBlockRecipes {

    private static ClientRightClickBlockRecipeInputs inputs;

    public static RightClickBlockRecipeInputs inputs() {
        return ClientRightClickBlockRecipes.inputs;
    }

    @SubscribeEvent // on the game event bus only on the physical client
    public static void recipesReceived(RecipesReceivedEvent event) {
        // Store the recipes
        Set<BlockState> inputStates = new HashSet<>();
        Set<Holder<Item>> inputItems = new HashSet<>();

        event.getRecipeMap().byType(RIGHT_CLICK_BLOCK_TYPE.get())
            .forEach(holder -> {
                var recipe = holder.value();
                inputStates.add(recipe.getInputState());
                inputItems.addAll(recipe.getInputItem().items());
            });
        
        ClientRightClickBlockRecipes.inputs = new ClientRightClickBlockRecipeInputs(
            inputStates, inputItems
        );
    }

    @SubscribeEvent // on the game event bus only on the physical client
    public static void clientLogOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Clear the stored inputs on world log out
        ClientRightClickBlockRecipes.inputs = null;
    }
}

public class RightClickBlockRecipes {
    // Make proxy method to access properly
    public static RightClickBlockRecipeInputs inputs(Level level) {
        return level.isClientSide()
            ? ClientRightClickBlockRecipes.inputs()
            : ServerRightClickBlockRecipes.inputs();
    }
}
```

Then, using the synced inputs, we can check the game for the used inputs:

```java
@SubscribeEvent // on the game event bus
public static void useItemOnBlock(UseItemOnBlockEvent event) {
    // Skip if we are not in the block-dictated phase of the event. See the event's javadocs for details.
    if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.BLOCK) return;
    // Get parameters to check input first
    Level level = event.getLevel();
    BlockPos pos = event.getPos();
    BlockState blockState = level.getBlockState(pos);
    ItemStack itemStack = event.getItemStack();

    // Check if the input can result in a recipe on both sides
    if (!RightClickBlockRecipes.inputs(level).test(blockState, itemStack)) return;

    // If so, make sure on server before checking recipe
    if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
        // Create an input and query the recipe.
        RightClickBlockInput input = new RightClickBlockInput(blockState, itemStack);
        Optional<RecipeHolder<? extends Recipe<CraftingInput>>> optional = serverLevel.recipeAccess().getRecipeFor(
            // The recipe type.
            RIGHT_CLICK_BLOCK_TYPE.get(),
            input,
            level
        );
        ItemStack result = optional
            .map(RecipeHolder::value)
            .map(e -> e.assemble(input))
            .orElse(ItemStack.EMPTY);
        
        // If there is a result, break the block and drop the result in the world.
        if (!result.isEmpty()) {
            level.removeBlock(pos, false);
            ItemEntity entity = new ItemEntity(level,
                    // Center of pos.
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    result);
            level.addFreshEntity(entity);
        }
    }

    // Cancel the event to stop the interaction pipeline regardless of side.
    // Already made sure that there could be a result.
    event.cancelWithResult(InteractionResult.SUCCESS_SERVER);
}
```

## Data Generation

To create a recipe builder for your own recipe serializer(s), you need to implement `RecipeBuilder` and its methods. A common implementation, partially copied from vanilla, would look like this:

```java
// This class is abstract because there is a lot of per-recipe-serializer logic.
// It serves the purpose of showing the common part of all (vanilla) recipe builders.
public abstract class SimpleRecipeBuilder implements RecipeBuilder {
    // Make the fields protected so our subclasses can use them.
    protected final ItemStackTemplate result;
    protected String group = "";
    protected boolean showNotification = true;

    // Provides a common way to build the recipe unlock advancement.
    // If used, the builder must also specify a `RecipeCategory` to determine
    // the output folder.
    protected final RecipeUnlockAdvancementBuilder advancementBuilder;
    protected final RecipeCategory category;

    // It is common for constructors to accept the result item stack template.
    // Alternatively, static builder methods are also possible.
    public SimpleRecipeBuilder(ItemStackTemplate result, RecipeCategory category) {
        this.result = result;
        this.category = category;
        this.advancementBuilder = new RecipeUnlockAdvancementBuilder();
    }

    // This method adds a criterion for the recipe advancement.
    @Override
    public SimpleRecipeBuilder unlockedBy(String name, Criterion<?> criterion) {
        this.criteria.put(name, criterion);
        return this;
    }

    // This method adds a recipe book group. If you do not want to use recipe book groups,
    // remove the this.group field and make this method no-op (i.e. return this).
    @Override
    public SimpleRecipeBuilder group(@Nullable String group) {
        this.group = Objects.requireNonNullElse(group, "");
        return this;
    }

    // This method sets whether to show the notification toast when unlocking. If you want
    // this value to be hardcoded, remove the this.showNotification field and this method.
    public SimpleRecipeBuilder showNotification(boolean showNotification) {
        this.showNotification = showNotification;
        return this;
    }

    // Returns the id of the recipe when using `#save(RecipeOutput)`.
    @Override
    public ResourceKey<Recipe<?>> defaultId() {
        // If the result is not an `ItemStackTemplate`, you will need to manually
        // construct the `ResourceKey` using the result.
        return RecipeBuilder.getDefaultRecipeId(this.result);
    }
}
```

So we have a base for our recipe builder. Now, before we continue with the recipe serializer-dependent part, we should first consider what to make our recipe factory. In our case, it makes sense to use the constructor directly. In other situations, using a static helper or a small functional interface is the way to go. This is especially relevant if you use one builder for multiple recipe classes.

Utilizing `RightClickBlockRecipe::new` as our recipe factory, and reusing the `SimpleRecipeBuilder` class above, we can create the following recipe builder for `RightClickBlockRecipe`s:

```java
public class RightClickBlockRecipeBuilder extends SimpleRecipeBuilder {
    private final BlockState inputState;
    private final Ingredient inputItem;

    // Since we have exactly one of each input, we pass them to the constructor.
    // Builders for recipe serializers that have ingredient lists of some sort would usually
    // initialize an empty list and have #addIngredient or similar methods instead.
    public RightClickBlockRecipeBuilder(ItemStackTemplate result, RecipeCategory category, BlockState inputState, Ingredient inputItem) {
        super(result, category);
        this.inputState = inputState;
        this.inputItem = inputItem;
    }

    // Saves a recipe using the given RecipeOutput and key. This method is defined in the RecipeBuilder interface.
    @Override
    public void save(RecipeOutput output, ResourceKey<Recipe<?>> key) {
        // Create the recipe.
        RightClickBlockRecipe recipe = new RightClickBlockRecipe(
            RecipeBuilder.createCraftingCommonInfo(this.showNotification),
            new RightClickBlockRecipe.BlockBookInfo(
                RecipeBuilder.determineCraftingBookCategory(this.category),
                this.group
            ),
            this.inputState,
            this.inputItem,
            this.result
        );

        // Pass the id, recipe, and the recipe advancement into the RecipeOutput.
        output.accept(key, recipe, this.advancementBuilder.build(output, key, this.category));
    }
}
```

And now, during [datagen][recipedatagen], you can call on your recipe builder like any other:

```java
@Override
protected void buildRecipes(RecipeOutput output) {
    new RightClickRecipeBuilder(
            // Our constructor parameters. This example adds the ever-popular dirt -> diamond conversion.
            new ItemStackTemplate(Items.DIAMOND),
            RecipeCategory.MISC,
            Blocks.DIRT.defaultBlockState(),
            Ingredient.of(Items.APPLE)
    )
            .unlockedBy("has_apple", this.has(Items.APPLE))
            .save(output);
    // other recipe builders here
}
```

:::note
It is also possible to have `SimpleRecipeBuilder` be merged into `RightClickBlockRecipeBuilder` (or your own recipe builder), especially if you only have one or two recipe builders. The abstraction here serves to show which parts of the builder are recipe-dependent and which are not.
:::

[clientrecipes]: index.md#client-side-recipes
[codec]: ../../../datastorage/codecs.md
[datagen]: #data-generation
[event]: ../../../concepts/events.md
[gui]: ../../../rendering/screens.md
[ingredients]: ingredients.md
[networking]: ../../../networking/payload.md
[recipedatagen]: index.md#data-generation
[registry]: ../../../concepts/registries.md#methods-for-registering
[serializer]: #the-recipe-serializer
[streamcodec]: ../../../networking/streamcodecs.md

## resources/server/recipes/index

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Recipes

Recipes are a way to transform a set of objects into other objects within a Minecraft world. Although Minecraft uses this system purely for item transformations, the system is built in a way that allows any kind of objects - blocks, entities, etc. - to be transformed. Almost all recipes use recipe data files; a "recipe" is assumed to be a data-driven recipe in this article unless explicitly stated otherwise.

Recipe data files are located at `data/<namespace>/recipe/<path>.json`. For example, the recipe `minecraft:diamond_block` is located at `data/minecraft/recipe/diamond_block.json`.

## Terminology

- A **recipe JSON**, or **recipe file**, is a JSON file that is loaded and stored by the `RecipeManager`. It contains info such as the recipe type, the inputs and outputs, as well as additional information (e.g. processing time).
- A **`Recipe`** holds in-code representations of all JSON fields, alongside the matching logic ("Does this input match the recipe?") and some other properties.
- A **`RecipeInput`** is a type that provides inputs to a recipe. Comes in several subclasses, e.g. `CraftingInput` or `SingleRecipeInput` (for furnaces and similar).
- A **recipe ingredient**, or just **ingredient**, is a single input for a recipe (whereas the `RecipeInput` generally represents a collection of inputs to check against a recipe's ingredients). Ingredients are a very powerful system and as such outlined [in their own article][ingredients].
- A **`PlacementInfo`** is a definition of items the recipe contains and what indexes they should populate. If the recipe cannot be captured to some degree based on the items provided (e.g., only changing the data components), then `PlacementInfo#NOT_PLACEABLE` is used.
- A **`SlotDisplay`** defines how a single slot should display within a recipe viewer, like the recipe book.
- A **`RecipeDisplay`** defines the `SlotDisplay`s of a recipe to be consumed by a recipe viewer, like the recipe book. While the interface only contains methods for the result of a recipe and the workstation the recipe was conducted within, a subtype can capture information like ingredients or grid size.
- The **`RecipeManager`** is a singleton field on the server that holds all loaded recipes.
- A **`RecipeSerializer`** is basically a wrapper around a [`MapCodec`][codec] and a [`StreamCodec`][streamcodec], both used for serialization.
- A **`RecipeType`** is the registered type equivalent of a `Recipe`. It is mainly used when looking up recipes by type. As a rule of thumb, different crafting containers should use different `RecipeType`s. For example, the `minecraft:crafting` recipe type covers the `minecraft:crafting_shaped` and `minecraft:crafting_shapeless` recipe serializers, as well as the special crafting serializers.
- A **`RecipeBookCategory`** is a group representing some recipes when viewed through a recipe book.
- A **recipe [advancement]** is an advancement responsible for unlocking a recipe in the recipe book. They are not required, and generally neglected by players in favor of recipe viewer mods, however the [recipe data provider][datagen] generates them for you, so it's recommended to just roll with it.
- A **`RecipePropertySet`** defines the available list of ingredients that can be accepted by the defined input slot in a menu.
- A **`RecipeBuilder`** is used during datagen to create JSON recipes.
- A **recipe factory** is a method reference used to create a `Recipe` from a `RecipeBuilder`. It can either be a reference to a constructor, or a static builder method, or a functional interface (often named `Factory`) created specifically for this purpose.

## JSON Specification

The contents of recipe files vary greatly depending on the selected type. Common to all recipe files are the `type` and [`neoforge:conditions`][conditions] properties:

```json5
{
    // The recipe type. This maps to an entry in the recipe serializer registry.
    "type": "minecraft:crafting_shaped",
    // A list of data load conditions. Optional, NeoForge-added. See the article linked above for more information.
    "neoforge:conditions": [ /*...*/ ]
}
```

A full list of types provided by Minecraft can be found in the [Built-In Recipe Types article][builtin]. Mods can also [define their own recipe types][customrecipes].

## Using Recipes

Recipes are loaded, stored and obtained via the `RecipeManager` class, which is in turn obtained via `ServerLevel#recipeAccess` or - if you don't have a `ServerLevel` available - `ServerLifecycleHooks.getCurrentServer()#getRecipeManager`. The server does not sync the recipes to the client by default, instead it only sends the `RecipePropertySet`s for restricting inputs on menu slots. Additionally, whenever a recipe is unlocked for the recipe book, its `RecipeDisplay`s and the corresponding `RecipeDisplayEntry`s are sent to the client (excluding all recipes where `Recipe#isSpecial` returns true) As such, recipe logic should always run on the server.

The easiest way to get a recipe is by its resource key:

```java
RecipeManager recipes = serverLevel.recipeAccess();
// RecipeHolder<?> is a record of the resource key and the recipe itself.
Optional<RecipeHolder<?>> optional = recipes.byKey(
    ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace("diamond_block"))
);
optional.map(RecipeHolder::value).ifPresent(recipe -> {
    // Do whatever you want to do with the recipe here. Be aware that the recipe may be of any type.
});
```

A more practically applicable method is constructing a `RecipeInput` and trying to get a matching recipe. In this example, we will be creating a `CraftingInput` containing one diamond block using `CraftingInput#of`. This will create a shapeless input, a shaped input would instead use `CraftingInput#ofPositioned`, and other inputs would use other `RecipeInput`s (for example, furnace recipes will generally use `new SingleRecipeInput`).

```java
RecipeManager recipes = serverLevel.recipeAccess();
// Construct a RecipeInput, as required by the recipe. For example, construct a CraftingInput for a crafting recipe.
// The parameters are width, height and items, respectively.
CraftingInput input = CraftingInput.of(1, 1, List.of(new ItemStack(Items.DIAMOND_BLOCK)));
// The generic wildcard on the recipe holder should then extend CraftingRecipe.
// This allows for more type safety later on.
Optional<RecipeHolder<? extends CraftingRecipe>> optional = recipes.getRecipeFor(
        // The recipe type to get the recipe for. In our case, we use the crafting type.
        RecipeType.CRAFTING,
        // Our recipe input.
        input,
        // Our level context.
        serverLevel
);
// This returns the diamond block -> 9 diamonds recipe (unless a datapack changes that recipe).
optional.map(RecipeHolder::value).ifPresent(recipe -> {
    // Do whatever you want here. Note that the recipe is now a CraftingRecipe instead of a Recipe<?>.
});
```

Alternatively, you can also get yourself a potentially empty list of recipes that match your input, this is especially useful for cases where it can be reasonably assumed that multiple recipes match:

```java
RecipeManager recipes = serverLevel.recipeAccess();
CraftingInput input = CraftingInput.of(1, 1, List.of(new ItemStack(Items.DIAMOND_BLOCK)));
// These are not Optionals, and can be used directly. However, the list may be empty, indicating no matching recipes.
Stream<RecipeHolder<? extends Recipe<CraftingInput>>> list = recipes.recipeMap().getRecipesFor(
    // Same parameters as above.
    RecipeType.CRAFTING, input, serverLevel
);
```

Once we have our correct recipe inputs, we also want to get the recipe outputs. This is done by calling `Recipe#assemble`:

```java
RecipeManager recipes = serverLevel.recipeAccess();
CraftingInput input = CraftingInput.of(...);
Optional<RecipeHolder<? extends CraftingRecipe>> optional = recipes.getRecipeFor(...);
// Use ItemStack.EMPTY as a fallback.
ItemStack result = optional
        .map(RecipeHolder::value)
        .map(recipe -> recipe.assemble(input))
        .orElse(ItemStack.EMPTY);
```

If necessary, it is also possible to iterate over all recipes of a type. This is done like so:

```java
RecipeManager recipes = serverLevel.recipeAccess();
// Like before, pass the desired recipe type.
Collection<RecipeHolder<?>> list = recipes.recipeMap().byType(RecipeType.CRAFTING);
```

## Recipe Priorities

Sometimes, recipes can overlap with others, usually because one pattern uses a specific item while another same pattern uses a tag that has the item within. In these instances, vanilla uses the first recipe it finds, which is determined by whatever recipe is read and loaded first. This can be an issue, as if the specific item recipe is loaded after the tag-based recipe, then the specific item recipe can never be obtained.

To combat this issue, NeoForge introduces recipe priorities to order which recipes should be displayed first. The entries are represented as a map of recipe registry keys to integer priority values. The priority values are sorted based on the highest value, recipes not specified defaulting to `0`. This means that recipes with a priority greater than `0` are ordered first, while recipes less than `0` are ordered last. The priority map is located in `data/<namespace>/recipe_priorities.json`, where all the recipe priorities are merged together, unless `replace` is true, which will clear out all previously loaded entries.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
{
    // When true, clears out all previously loaded entries.
    "replace": false,
    // The map of recipe entries to their priority values.
    // If a recipe does not have a priority, it defaults to 0.
    "entries": {
        // Points to 'data/examplemod/recipe/higher_priority.json'
        // This recipe will be checked before any defaults.
        "examplemod:higher_priority": 1,
        // Points to 'data/examplemod/recipe/lower_priority.json'
        // This recipe will be checked after any defaults.
        "examplemod:lower_priority": -1,
        // Points to 'data/examplemod/recipe/even_lower_priority.json'
        // This recipe will be checked after any defaults and the
        // 'lower_priority' recipe.
        "examplemod:even_lower_priority": -2
    }
}
```

</TabItem>
<TabItem value="datagen" label="Datagen">

```java
// Generates the recipe priorities
public class ExamplePrioritiesProvider extends RecipePrioritiesProvider {

    public ExamplePrioritiesProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        // Replace 'examplemod' with your mod id.
        super(output, registries, "examplemod");
    }

    @Override
    protected void start() {
        // Registers a recipe entry to a priority value.

        this.add(
            // Points to 'data/examplemod/recipe/higher_priority.json'
            ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("examplemod", "higher_priority")),
            // This recipe will be checked before any defaults.
            1
        );

        this.add(
            // Points to 'data/examplemod/recipe/lower_priority.json'
            Identifier.fromNamespaceAndPath("examplemod", "lower_priority"),
            // This recipe will be checked after any defaults.
            -1
        );

        this.add(
            // Points to 'data/examplemod/recipe/even_lower_priority.json'
            // The namespace is inferred from the mod id passed to the provider.
            "even_lower_priority",
            // This recipe will be checked after any defaults and the 'lower_priority' recipe.
            -2
        );
    }
}
```

</TabItem>
</Tabs>

## Other Recipe Mechanisms

Some mechanisms in vanilla are generally considered recipes, but are implemented differently in code. This is generally either due to legacy reasons, or because the "recipes" are constructed from other data (e.g. [tags]).

:::warning
Recipe viewer mods will generally not pick up these recipes. Support for these mods must be added manually, please see the corresponding mod's documentation for more information.
:::

### Anvil Recipes

Anvils have two input slots and one output slot. The only vanilla use cases are tool repairing, combining and renaming, and since each of these use cases needs special handling, no recipe files are provided. However, the system can be built upon using `AnvilUpdateEvent`. This [event] allows getting the input (left input slot) and material (right input slot) and allows setting an output item stack, as well as the experience cost and the number of materials to consume. The process can also be prevented as a whole by [canceling][cancel] the event.

```java
// This example allows repairing a stone pickaxe with a full stack of dirt, consuming half the stack, for 3 levels.
@SubscribeEvent // on the game event bus
public static void onAnvilUpdate(AnvilUpdateEvent event) {
    ItemStack left = event.getLeft();
    ItemStack right = event.getRight();
    if (left.is(Items.STONE_PICKAXE) && right.is(Items.DIRT) && right.getCount() >= 32) {
        event.setOutput(new ItemStack(Items.STONE_PICKAXE));
        event.setMaterialCost(32);
        event.setXpCost(3);
    }
}
```

### Brewing

See [the Brewing chapter in the Mob Effects & Potions article][brewing].

### Extending the Crafting Grid Size

The `ShapedRecipePattern` class, responsible for holding the in-memory representation of shaped crafting recipes, has a hardcoded limit of 3x3 slots, hindering mods that want to add larger crafting tables while reusing the vanilla shaped crafting recipe type. To solve this problem, NeoForge patches in a static method called `ShapedRecipePattern#setCraftingSize(int width, int height)` that allows increasing the limit. It should be called during `FMLCommonSetupEvent`. The biggest value wins here, so for example if one mod added a 4x6 crafting table and another added a 6x5 crafting table, the resulting values would be 6x6.

:::danger
`ShapedRecipePattern#setCraftingSize` is not thread-safe. It must be wrapped in an `event#enqueueWork` call.
:::

### Client-Side Recipes

By default, vanilla does not send any recipes to the [logical client][logicalside]. Instead, the `RecipePropertySet` / `SelectableRecipe.SingleInputSet` is synced to handle proper client-side behavior during user interactions. Additionally, when a recipe is unlocked in the recipe book, its `RecipeDisplay` is synced. However, these two cases are limited in scope, especially when more data is needed from the recipe itself. In these instances, NeoForge provides a way to send the full recipes for a given `RecipeType` to the client.

There are two events that must be listened to on the [game event bus][events]: `OnDatapackSyncEvent` and `RecipesReceivedEvent`. First, specify the `RecipeType`s to sync to the client by calling `OnDatapackSyncEvent#sendRecipes`. Then, the recipes can be accessed from the provided `RecipeMap` via `RecipesReceivedEvent#getRecipeMap`. Additionally, any recipes stored on the client should be cleared once the player logs out of the world via `ClientPlayerNetworkEvent.LoggingOut`.

```java
// Assume we have some custom RecipeType<ExampleRecipe> EXAMPLE_RECIPE_TYPE

@SubscribeEvent // on the game event bus
public static void datapackSync(OnDatapackSyncEvent event) {
    // Specify what recipe types to sync to the client
    event.sendRecipes(EXAMPLE_RECIPE_TYPE);
}

// In some class only on the physical client

private static final List<RecipeHolder<ExampleRecipe>> EXAMPLE_RECIPES = new ArrayList<>();

@SubscribeEvent // on the game event bus only on the physical client
public static void recipesReceived(RecipesReceivedEvent event) {
    // First remove the previous recipes
    EXAMPLE_RECIPES.clear();

    // Then store the recipes you want
    EXAMPLE_RECIPES.addAll(event.getRecipeMap().byType(EXAMPLE_RECIPE_TYPE));
}

@SubscribeEvent // on the game event bus only on the physical client
public static void clientLogOut(ClientPlayerNetworkEvent.LoggingOut event) {
    // Clear the stored recipes on world log out
    EXAMPLE_RECIPES.clear();
}
```

:::warning
If you are planning on syncing recipes for your recipe type, `OnDatapackSyncEvent` should be called on both physical sides. All worlds, including singleplayer, have a delineation between the server and client, meaning that referencing a datapack registry entry from the server on the client will likely crash the game.
:::

## Data Generation

Like most other JSON files, recipes can be datagenned. For recipes, we want to extend the `RecipeProvider` class and override `#buildRecipes`, and extend the `RecipeProvider.Runner` class to pass to the data generator:

```java
public class MyRecipeProvider extends RecipeProvider {

    // Construct the provider to run
    protected MyRecipeProvider(HolderLookup.Provider provider, RecipeOutput output) {
        super(provider, output);
    }
 
    @Override
    protected void buildRecipes() {
        // Add your recipes here.
    }

    // The runner to add to the data generator
    public static class Runner extends RecipeProvider.Runner {
        // Get the parameters from the `GatherDataEvent`s.
        public Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
            super(output, lookupProvider);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider provider, RecipeOutput output) {
            return new MyRecipeProvider(provider, output);
        }
    }
}
```

Of note is the `RecipeOutput` parameter. Minecraft uses this object to automatically generate a recipe advancement for you. On top of that, NeoForge injects [conditions] support into `RecipeOutput`, which can be called on via `#withConditions`.

Recipes themselves are commonly added through subclasses of `RecipeBuilder`. Listing all vanilla recipe builders is beyond the scope of this article (they are explained in the [Built-In Recipe Types article][builtin]), however creating your own builder is explained [in the custom recipes page][customdatagen].

Like all other data providers, recipe providers must be registered to the `GatherDataEvent`s like so:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createProvider(MyRecipeProvider.Runner::new);
}
```

The recipe provider also adds helpers for common scenarios, such as `twoByTwoPacker` (for 2x2 block recipes), `threeByThreePacker` (for 3x3 block recipes) or `nineBlockStorageRecipes` (for 3x3 block recipes and 1 block to 9 items recipes).

[advancement]: ../advancements.md
[brewing]: ../../../items/mobeffects.md#brewing
[builtin]: builtin.md
[cancel]: ../../../concepts/events.md#cancellable-events
[codec]: ../../../datastorage/codecs.md
[conditions]: ../conditions.md
[customdatagen]: custom.md#data-generation
[customrecipes]: custom.md
[datagen]: #data-generation
[event]: ../../../concepts/events.md
[ingredients]: ingredients.md
[logicalside]: ../../../concepts/sides.md#the-logical-side
[streamcodec]: ../../../networking/streamcodecs.md
[tags]: ../tags.md

## resources/server/recipes/ingredients

# Ingredients

`Ingredient`s are used in [recipes] to check whether a given [`ItemStack`][itemstack] is a valid input for the recipe. For this purpose, `Ingredient` implements `Predicate<ItemStack>`, and `#test` can be called to confirm if a given `ItemStack` matches the ingredient.

Unfortunately, many internals of `Ingredient` are a mess. NeoForge works around this by ignoring the `Ingredient` class where possible, instead introducing the `ICustomIngredient` interface for custom ingredients. This is not a direct replacement for regular `Ingredient`s, but we can convert to and from `Ingredient`s using `ICustomIngredient#toVanilla` and `Ingredient#getCustomIngredient`, respectively.

## Built-In Ingredient Types

The simplest way to get an ingredient is using the `Ingredient#of` helpers. Several variants exist:

- `Ingredient.of()` returns an empty ingredient.
- `Ingredient.of(Blocks.IRON_BLOCK, Items.GOLD_BLOCK)` returns an ingredient that accepts either an iron or a gold block. The parameter is a vararg of [`ItemLike`s][itemlike], which means that any amount of both blocks and items may be used.
- `Ingredient.of(Stream.of(Items.DIAMOND_SWORD))` returns an ingredient that accepts an item. Like the previous method, but with a `Stream<ItemLike>` for if you happen to get your hands on one of those.
- `Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.WOODEN_SLABS))` returns an ingredient that accepts any item from the specified [tag], for example any wooden slab.

Additionally, NeoForge adds a few additional ingredients:

- `new BlockTagIngredient(BlockTags.CONVERTABLE_TO_MUD)` returns an ingredient similar to the tag variant of `Ingredient.of()`, but with a block tag instead. This should be used for cases where you'd use an item tag, but there is only a block tag available (for example `minecraft:convertable_to_mud`).
- `CustomDisplayIngredient.of(Ingredient.of(Items.DIRT), SlotDisplay.Empty.INSTANCE)` returns an ingredient with a custom [`SlotDisplay`][slotdisplay] you provide to determine how the slot gets consumed for rendering on the client.
- `CompoundIngredient.of(Ingredient.of(Items.DIRT))` returns an ingredient with child ingredients, passed in the constructor (vararg parameter). The ingredient matches if any of its children matches.
- `DataComponentIngredient.of(true, new ItemStack(Items.DIAMOND_SWORD))` returns an ingredient that, in addition to the item, also matches the data component. The boolean parameter denotes strict matching (true) or partial matching (false). Strict matching means the data components must match exactly, while partial matching means the data components must match, but other data components may also be present. Additional overloads of `#of` exist that allow specifying multiple `Item`s, or provide other options.
- `DifferenceIngredient.of(Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.PLANKS)), Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.NON_FLAMMABLE_WOOD)))` returns an ingredient that matches everything in the first ingredient that doesn't also match the second ingredient. The given example only matches planks that can burn (i.e. all planks except crimson planks, warped planks and modded nether wood planks).
- `IntersectionIngredient.of(Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.PLANKS)), Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.NON_FLAMMABLE_WOOD)))` returns an ingredient that matches everything that matches both sub-ingredients. The given example only matches planks that cannot burn (i.e. crimson planks, warped planks and modded nether wood planks).

:::note
If you are using data generation with ingredients that take in a `HolderSet` for the tag instance (the ones that call `Registry#getOrThrow`), then that `HolderSet` should be obtained via the `HolderLookup.Provider`, using `HolderLookup.Provider#lookupOrThrow` to get the item registry and `HolderGetter#getOrThrow` with the `TagKey` to get the holder set.
:::

Keep in mind that the NeoForge-provided ingredient types are `ICustomIngredient`s and must call `#toVanilla` before using them in vanilla contexts, as outlined in the beginning of this article.

## Custom Ingredient Types

It is possible for modders to add their custom ingredient types through the `ICustomIngredient` system. For the sake of example, let's make an enchanted item ingredient that accepts an item tag and a map of enchantments to min levels:

```java
public class MinEnchantedIngredient implements ICustomIngredient {
    private final TagKey<Item> tag;
    private final Map<Holder<Enchantment>, Integer> enchantments;
    // The codec for serializing the ingredient.
    public static final MapCodec<MinEnchantedIngredient> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            TagKey.codec(Registries.ITEM).fieldOf("tag").forGetter(e -> e.tag),
            Codec.unboundedMap(Enchantment.CODEC, Codec.INT)
                    .optionalFieldOf("enchantments", Map.of())
                    .forGetter(e -> e.enchantments)
    ).apply(inst, MinEnchantedIngredient::new));
    // Create a stream codec from the regular codec. In some cases, it might make sense to define
    // a new stream codec from scratch.
    public static final StreamCodec<RegistryFriendlyByteBuf, MinEnchantedIngredient> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());

    // Allow passing in a pre-existing map of enchantments to levels.
    public MinEnchantedIngredient(TagKey<Item> tag, Map<Holder<Enchantment>, Integer> enchantments) {
        this.tag = tag;
        this.enchantments = enchantments;
    }

    // Check if the passed ItemStack matches our ingredient by verifying the item is in the tag
    // and by testing for presence of all required enchantments with at least the required level.
    @Override
    public boolean test(ItemStack stack) {
        return stack.is(tag) && enchantments.keySet()
                .stream()
                .allMatch(ench -> EnchantmentHelper.getEnchantmentsForCrafting(stack).getLevel(ench) >= enchantments.get(ench));
    }

    // Determines whether this ingredient performs NBT or data component matching (false) or not (true).
    // Also determines whether a stream codec is used for syncing, more on this later.
    // We query enchantments on the stack, therefore our ingredient is not simple.
    @Override
    public boolean isSimple() {
        return false;
    }

    // Returns a stream of items that match this ingredient. Mostly for display purposes.
    // There's a few good practices to follow here:
    // - Always include at least one item, to prevent accidental recognition as empty.
    // - Include each accepted Item at least once.
    // - If #isSimple is true, this should be exact and contain every item that matches.
    //   If not, this should be as exact as possible, but doesn't need to be super accurate.
    // In our case, we use all items in the tag.
    @Override
    public Stream<Holder<Item>> items() {
        return BuiltInRegistries.ITEM.getOrThrow(tag).stream();
    }
}
```

Custom ingredients are a [registry], so we must register our ingredient. We do so using the `IngredientType` class provided by NeoForge, which is basically a wrapper around a [`MapCodec`][codec] and optionally a [`StreamCodec`][streamcodec].

```java
public static final DeferredRegister<IngredientType<?>> INGREDIENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.INGREDIENT_TYPE, ExampleMod.MOD_ID);

public static final Supplier<IngredientType<MinEnchantedIngredient>> MIN_ENCHANTED =
        INGREDIENT_TYPES.register("min_enchanted",
                // The stream codec parameter is optional, a stream codec will be created from the codec
                // using ByteBufCodecs#fromCodec or #fromCodecWithRegistries if the stream codec isn't specified.
                () -> new IngredientType<>(MinEnchantedIngredient.CODEC, MinEnchantedIngredient.STREAM_CODEC));
```

When we have done that, we also need to override `#getType` in our ingredient class:

```java
public class MinEnchantedIngredient implements ICustomIngredient {
    // other stuff here

    @Override    
    public IngredientType<?> getType() {
        return MIN_ENCHANTED.get();
    }
}
```

And there we go! Our ingredient type is ready to use.

## JSON Representation

Due to vanilla ingredients being pretty limited and NeoForge introducing a whole new registry for them, it's also worth looking at what the built-in and our own ingredients look like in JSON.

Ingredients that are objects and specify a `neoforge:ingredient_type` are generally assumed to be non-vanilla. For example:

```json5
{
    "neoforge:ingredient_type": "neoforge:block_tag",
    "tag": "minecraft:convertable_to_mud"
}
```

Or another example using our own ingredient:

```json5
{
    "neoforge:ingredient_type": "examplemod:min_enchanted",
    "tag": "c:swords",
    "enchantments": {
        "minecraft:sharpness": 4
    }
}
```

If the ingredient is a string, meaning `neoforge:ingredient_type` is unspecified, then we have a vanilla ingredient. Vanilla ingredients are strings that either represent an item, or a tag when prefixed with `#`.

An example for a vanilla item ingredient:

```json5
"minecraft:dirt"
```

An example for a vanilla tag ingredient:

```json5
"#c:ingots"
```

[codec]: ../../../datastorage/codecs.md
[itemlike]: ../../../items/index.md#itemlike
[itemstack]: ../../../items/index.md#itemstacks
[recipes]: index.md
[registry]: ../../../concepts/registries.md
[slotdisplay]: index.md#slot-displays
[streamcodec]: ../../../networking/streamcodecs.md
[tag]: ../tags.md

## resources/server/tags

# Tags

A tag is, simply put, a list of registered objects of the same type. They are loaded from data files and can be used for membership checks. For example, crafting sticks will accept any combination of wooden planks (items tagged with `minecraft:planks`). Tags are often distinguished from "regular" objects by prefixing them with a `#` (for example `#minecraft:planks`, but `minecraft:oak_planks`).

Any [registry] can have tag files - while blocks and items are the most common use cases, other registries such as fluids, entity types or damage types often utilize tags as well. You can also create your own tags if you need them.

Tags are located at `data/<tag_namespace>/tags/<registry_path>/<tag_path>.json` for Minecraft registries, and `data/<tag_namespace>/tags/<registry_namespace>/<registry_path>/<tag_path>.json` for non-Minecraft registries. For example, to modify the `minecraft:planks` item tag, you would place your tag file at `data/minecraft/tags/item/planks.json`.

:::info
Unlike most other NeoForge data files, NeoForge-added tags do generally not use the `neoforge` namespace. Instead, they use the `c` namespace (e.g. `c:ingots/gold`). This is because the tags are unified between NeoForge and the Fabric mod loader, at the request of many modders developing on multiple loaders.

There are a few exceptions to this rule for some tags that tie closely into NeoForge systems. This includes many [damage type][damagetype] tags, for example.
:::

Overriding tag files is generally additive instead of replacing. This means that if two datapacks specify tag files with the same id, the contents of both files will be merged (unless otherwise specified). This behavior sets tags apart from most other data files, which instead replace any and all existing values.

## Tag File Format

Tag files have the following syntax:

```json5
{
    // The values of the tag.
    "values": [
        // A value object. Must specify the id of the object to add, and whether it is required.
        // If the entry is required, but the object is not present, the tag will not load. The "required" field
        // is technically optional, but when removed, the entry is equivalent to the shorthand below.
        {
            "id": "examplemod:example_ingot",
            "required": false
        }
        // Shorthand for {"id": "minecraft:gold_ingot", "required": true}, i.e. a required entry.
        "minecraft:gold_ingot",
        // A tag object. Distinguished from regular entries by the leading #. In this case, all planks
        // will be considered entries of the tag. Like normal entries, this can also have the "id"/"required" format.
        // Warning: Circular tag dependencies will lead to a datapack not being loaded!
        "#minecraft:planks"
    ],
    // Whether to remove all pre-existing entries before adding your own (true) or just add your own (false).
    // This should generally be false, the option to set this to true is primarily aimed at pack developers.
    "replace": false,
    // A finer-grained way to remove entries from the tag again, if present. Optional, NeoForge-added.
    // Entry syntax is the same as in the "values" array.
    "remove": [
        "minecraft:iron_ingot"
    ]
}
```

## Finding and Naming Tags

When you try to find an existing tag, it is generally recommended to follow these steps:

- Have a look at Minecraft's tags and see if the tag you're looking for is there. Minecraft's tags can be found in `BlockTags`, `ItemTags`, `EntityTypeTags` etc.
- If not, have a look at NeoForge's tags and see if the tag you're looking for is there. NeoForge's tags can be found in `Tags.Blocks`, `Tags.Items`, `Tags.EntityTypes`, etc.
- Otherwise, assume the tag is not specified in Minecraft or NeoForge, and thus you need to create your own tag.

When creating your own tag, you should ask yourself the following questions:

- Does this modify my mod's behavior? If yes, the tag should be in your mod's namespace. (This is common e.g. for my-thing-can-spawn-on-this-block kind of tags.)
- Would other mods want to use this tag as well? If yes, the tag should be in the `c` namespace. (This is common e.g. for new metals or gems.)
- Otherwise, use your mod's namespace.

Naming the tag itself also has some conventions to follow:

- Use the plural form. E.g.: `minecraft:planks`, `c:ingots`.
- Use folders for multiple objects of the same type, and an overall tag for each folder. E.g.: `c:ingots/iron`, `c:ingots/gold`, and `c:ingots` containing both. (Note: This is a NeoForge convention, Minecraft does not follow this convention for most tags.)

## Using Tags

To reference tags in code, you must create a `TagKey<T>`, where `T` is the type of tag (`Block`, `Item`, `EntityType<?>`, etc.), using a [registry key][regkey] and an [identifier][identifier]:

```java
public static final TagKey<Block> MY_TAG = TagKey.create(
        // The registry key. The type of the registry must match the generic type of the tag.
        Registries.BLOCK,
        // The location of the tag. This example will put our tag at data/examplemod/tags/blocks/example_tag.json.
        Identifier.fromNamespaceAndPath("examplemod", "example_tag")
);
```

:::warning
Since `TagKey` is a record, its constructor is public. However, the constructor should not be used directly, as doing so can lead to various issues, for example when looking up tag entries.
:::

We can then use our tag to perform various operations on it. Let's start with the most obvious one: check whether an object is in the tag. The following examples will assume block tags, but the functionality is the exact same for every type of tag (unless otherwise specified):

```java
// Check whether dirt is in our tag.
// Assume access to Level level
boolean isInTag = level.registryAccess().lookupOrThrow(BuiltInRegistries.BLOCK).getOrThrow(MY_TAG).stream().anyMatch(holder -> holder.is(Items.DIRT));
```

Since this is a very verbose statement, especially when used often, `BlockState` and `ItemStack` - the two most common users of the tag system - each define a `#is` helper method, used like so:

```java
// Check whether the blockState's block is in our tag.
boolean isInBlockTag = blockState.is(MY_TAG);
// Check whether the itemStack's item is in our tag. Assumes the existence of MY_ITEM_TAG as a TagKey<Item>.
boolean isInItemTag = itemStack.is(MY_ITEM_TAG);
```

If needed, we can also get ourselves a set of tag entries to stream, like so:

```java
// Assume access to Level level
Stream<Holder<Block>> blocksInTag = level.registryAccess().lookupOrThrow(BuiltInRegistries.BLOCK).getOrThrow(MY_TAG).stream();
```

### Tags for Static Registries During Bootstrap

Sometimes, you need to get access to a `HolderSet` during the registry process. In [data component contexts][datacomponent], a `HolderLookup.Provider` is provided as part of the initializer to resolve:

```java
Item.Properties props = new Item.Properties().delayedComponent(
    // The component to initialize
    DataComponents.DAMAGE_RESISTANT,
    // The initializer function, typically provides at least the registry lookup
    registries -> new DamageResistant(
        // Get the HolderSet from the TagKey
        registries.getOrThrow(DamageTypeTags.IS_FIRE)
    )
);
```

Outside of the data component context, for static registries **only**, you can obtain the necessary `HolderGetter` via `BuiltInRegistries#acquireBootstrapRegistrationLookup`:

```java
// Assume access to Level level
HolderSet<Block> blockTag = BuiltInRegistries.acquireBootstrapRegistrationLookup(BuiltInRegistries.BLOCK).getOrThrow(MY_TAG);
```

## Datagen

Like many other JSON files, tags can be [datagenned][datagen]. Each kind of tag has its own datagen base class - one class for block tags, one for item tags, etc. -, and as such, we need one class for each kind of tag as well. All of these classes extend from the `TagsProvider<T>` base class, with `T` again being the type of the tag (`Block`, `Item`, etc.) The `TagsProvider`s are then further grouped into two main categories: `IntrinsicHolderTagsProvider<T>` for typically static registry objects, allowing you to directly pass the object to the tag; and `KeyTagProvider` for typically datapack registry objects, allowing you to pass the `ResourceKey` of an object to the tag. There is also an additional category `HolderTagProvider<T>` for `Holder`-wrapped static registry objects, though this is only used by vanilla for potion tags.

The following table shows a list of tag providers for different objects:

| Type                       | Tag Provider Class                     | Provider Type                 |
|----------------------------|----------------------------------------|-------------------------------|
| `BannerPattern`            | `BannerPatternTagsProvider`            | `KeyTagProvider`              |
| `Biome`                    | `BiomeTagsProvider`                    | `KeyTagProvider`              |
| `Block`                    | `BlockTagsProvider`\*                  | `IntrinsicHolderTagsProvider` |
| `ConfiguredFeature`        | `FeatureTagsProvider`                  | `KeyTagProvider`              |
| `DamageType`               | `DamageTypeTagsProvider`               | `KeyTagProvider`              |
| `Dialog`                   | `DialogTagsProvider`                   | `KeyTagProvider`              |
| `Enchantment`              | `EnchantmentTagsProvider`              | `KeyTagProvider`              |
| `EntityType`               | `EntityTypeTagsProvider`               | `IntrinsicHolderTagsProvider` |
| `FlatLevelGeneratorPreset` | `FlatLevelGeneratorPresetTagsProvider` | `IntrinsicHolderTagsProvider` |
| `Fluid`                    | `FluidTagsProvider`                    | `KeyTagProvider`              |
| `GameEvent`                | `GameEventTagsProvider`                | `KeyTagProvider`              |
| `Instrument`               | `InstrumentTagsProvider`               | `KeyTagProvider`              |
| `Item`                     | `ItemTagsProvider`\*                   | `IntrinsicHolderTagsProvider` |
| `PaintingVariant`          | `PaintingVariantTagsProvider`          | `KeyTagProvider`              |
| `PoiType`                  | `PoiTypeTagsProvider`                  | `KeyTagProvider`              |
| `Potion`                   | `PotionTagsProvider`                   | `HolderTagProvider`           |
| `Structure`                | `StructureTagsProvider`                | `KeyTagProvider`              |
| `Timeline`                 | `TimelineTagsProvider`                 | `KeyTagProvider`              |
| `VillagerTrade`            | `VillagerTradesTagsProvider`           | `KeyTagProvider`              |
| `WorldPreset`              | `WorldPresetTagsProvider`              | `KeyTagProvider`              |

\* These providers are provided by NeoForge.

For the sake of example, let's assume that we want to generate block tags (an intrinsic holder):

```java
public class MyBlockTagsProvider extends BlockTagsProvider {
    // Get parameters from one of the `GatherDataEvent`s.
    public MyBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, ExampleMod.MOD_ID);
    }

    // Add your tag entries here.
    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // Create a TagAppender of registry objects for our tag. This could also be e.g. a vanilla or NeoForge tag.
        this.tag(MY_TAG)
            // Add entries. This is a vararg parameter.
            // Key tag providers must provide ResourceKeys here instead of the actual objects.
            .add(Blocks.DIRT, Blocks.COBBLESTONE)
            // Add optional entries that will be ignored if absent. This example uses Botania's Pure Daisy.
            // This is not a vararg parameter.
            .add(TagEntry.optionalElement(Identifier.fromNamespaceAndPath("botania", "pure_daisy")))
            // Add a tag entry.
            .addTag(BlockTags.PLANKS)
            // Add multiple tag entries. This is a vararg parameter.
            // Can cause unchecked warnings that can safely be suppressed.
            .addTags(BlockTags.LOGS, BlockTags.WOODEN_SLABS)
            // Add an optional tag entry that will be ignored if absent.
            .addOptionalTag(ItemTags.create(Identifier.fromNamespaceAndPath("c", "ingots/tin")))
            // Add multiple optional tag entries. This is a vararg parameter.
            // Can cause unchecked warnings that can safely be suppressed.
            .addOptionalTags(ItemTags.create(Identifier.fromNamespaceAndPath("c", "nuggets/tin")), ItemTags.create(Identifier.fromNamespaceAndPath("c", "storage_blocks/tin")))
            // Set the replace property to true.
            .replace()
            // Set the replace property back to false.
            .replace(false)
            // Remove entries. This is a vararg parameter.
            // Key tag providers must provide ResourceKeys here instead of the actual objects.
            // Can cause unchecked warnings that can safely be suppressed.
            .remove(Blocks.CRIMSON_SLAB, Blocks.WARPED_SLAB);
    }
}
```

This example results in the following tag JSON:

```json5
{
    "values": [
        "minecraft:dirt",
        "minecraft:cobblestone",
        {
            "id": "botania:pure_daisy",
            "required": false
        },
        "#minecraft:planks",
        "#minecraft:logs",
        "#minecraft:wooden_slabs",
        {
            "id": "c:ingots/tin",
            "required": false
        },
        {
            "id": "c:nuggets/tin",
            "required": false
        },
        {
            "id": "c:storage_blocks/tin",
            "required": false
        }
    ],
    "remove": [
        "minecraft:crimson_slab",
        "minecraft:warped_slab"
    ]
}
```

Like all data providers, add each tag provider to the `GatherDataEvent`s:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createProvider(MyBlockTagsProvider::new);
}
```

### Custom Tag Providers

A custom tag provider, whether for an existing or custom [registry], can be created by simply extending `TagsProvider<T>`, where `T` is the registry object you are generating a tag for.

```java
public class MyRecipeTypeTagsProvider extends TagsProvider<RecipeType<?>> {
    // Get parameters from the `GatherDataEvent`s.
    public MyRecipeTypeTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        // Second parameter is the registry key we are generating the tags for.
        super(output, Registries.RECIPE_TYPE, lookupProvider, ExampleMod.MOD_ID);
    }
    
    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) { /*...*/ }
}
```

From here, tags are generated from the provider by creating a `TagBuilder` via `getOrCreateRawBuilder`. The builder contains methods to add or remove elements and tags by their `Identifier`. Additionally, the builder can specify the `replace` property via `setReplace`:

```java
public class MyRecipeTypeTagsProvider extends TagsProvider<RecipeType<?>> {
    
    // ...

    // Lets assume the following TagKey<RecipeType<?>> SMELTERS, CRAFTERS, SMITHERS
    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // Create a TagBuilder for `Identifier`s.
        this.getOrCreateRawBuilder(MY_TAG)
            // Add entries.
            .addElement(Identifier.fromNamespaceAndPath("minecraft", "crafting"))
            .addElement(Identifier.fromNamespaceAndPath("minecraft", "smelting"))
            // Add optional entries that will be ignored if absent.
            .addOptionalElement(Identifier.fromNamespaceAndPath("minecraft", "blasting"))
            // Add a tag entry.
            .addTag(SMELTERS.location())
            // Add an optional tag entry that will be ignored if absent.
            .addOptionalTag(CRAFTERS.location())
            // Set the replace property to true.
            .setReplace(true)
            // Set the replace property back to false.
            .setReplace(false)
            // Remove entries.
            .removeElement(Identifier.fromNamespaceAndPath("minecraft", "campfire_cooking"))
            // Remove a tag entry.
            .removeTag(SMITHERS.location());
    }
}
```

Currently, the entire tag is being constructed from `Identifier`s. However, specifying the raw identifier every time can become tedious, especially when the `ResourceKey` or the direct object is available. That's where `TagAppender` comes in. `TagAppender<E, T>` is functionally a wrapper around a `TagBuilder` that takes in some arbitrary entry object `E` and converts it into `TagBuilder` calls for the registry object `T`. The `TagAppender` can be remapped into any arbitrary object via `map`, provided there is a way to convert the new object type into the previous entry object `E`. This is basically what `KeyTagProvider` and `IntrinsicHolderTagsProvider` are doing. They provide a method `tag` that creates a `TagAppender` that maps `ResourceKey`s to `Identifier`s or direct objects to `Identifier`s, respectively:

```java

public class MyRecipeTypeTagsProvider extends TagsProvider<RecipeType<?>> {
    
    // ...

    // Let's assume we have the TagKey<RecipeType<?>>s SMELTERS, CRAFTERS, SMITHERS
    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // Create the TagAppender for `Identifier`s.
        this.tag(MY_TAG)
            // Replace property info
            .replace()
            // Handle any optional elements that may not be present
            .addOptional(Identifier.fromNamespaceAndPath("examplemod", "example_type"))
            // Can take in a TagKey
            .addOptionalTag(CRAFTERS)

            // Map to ResourceKey (KeyTagProvider)
            .map((Function<ResourceKey<RecipeType<?>>, Identifier>) ResourceKey::location)
            .add(BuiltInRegistries.RECIPE_TYPE.getResourceKey(RecipeType.CRAFTING).orElseThrow())

            // Map to direct object (IntrinsicHolderTagsProvider)
            .map((Function<RecipeType<?>, ResourceKey<RecipeType<?>>) type -> BuiltInRegistries.RECIPE_TYPE.getResourceKey(type).orElseThrow())
            .add(RecipeType.SMELTING)
            .addTag(SMELTERS)
            .remove(RecipeType.CAMPFIRE_COOKING)
            .remove(SMITHERS);
    }

    private TagAppender<Identifier, RecipeType<?>> tag(TagKey<RecipeType<?>> tag) {
        // Create the builder
        TagBuilder builder = this.getOrCreateRawBuilder(tag);

        // Generate the appender (can use TagAppender#forBuilder) instead
        return new TagAppender<Identifier, T>() {

            @Override
            public TagAppender<Identifier, T> add(Identifier element) {
                builder.addElement(element);
                return this;
            }

            @Override
            public TagAppender<Identifier, T> addOptional(Identifier element) {
                builder.addOptionalElement(element);
                return this;
            }

            @Override
            public TagAppender<Identifier, T> addTag(TagKey<T> tag) {
                builder.addTag(tag.location());
                return this;
            }

            @Override
            public TagAppender<Identifier, T> addOptionalTag(TagKey<T> tag) {
                builder.addOptionalTag(tag.location());
                return this;
            }

            // For situations where you cannot access the current entry object
            @Override
            public TagAppender<Identifier, T> add(TagEntry entry) {
                builder.add(entry);
                return this;
            }

            @Override
            public TagAppender<Identifier, T> replace(boolean value) {
                builder.setReplace(value);
                return this;
            }

            @Override
            public TagAppender<Identifier, T> remove(Identifier element) {
                builder.removeElement(element);
                return this;
            }

            @Override
            public TagAppender<ResourceKey<T>, T> remove(TagKey<T> tag) {
                builder.removeTag(tag.location());
                return this;
            }
        };
    }
}
```

#### Copying Tag Contents

NeoForge provides a special type of `IntrinsicHolderTagsProvider` called `BlockTagCopyingItemTagProvider`, intended for item tags that mirror the contents of its associated block tags. Instead of using the `TagAppender`, instead call `copy`, passing the block tag to copy to the item tag.

```java
public class ExampleBlockTagCopyingItemTagProvider extends BlockTagCopyingItemTagProvider {

    public ExampleBlockTagCopyingItemTagProvider(
        PackOutput output,
        CompletableFuture<HolderLookup.Provider> lookupProvider,
        CompletableFuture<TagLookup<Block>> blockTags // Obtained from BlockTagsProvider#contentsGetter
    ) {
        super(output, lookupProvider, blockTags, ExampleMod.MOD_ID);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // Assuming types TagKey<Block> and TagKey<Item> for the two parameters
        this.copy(EXAMPLE_BLOCK_TAG, EXAMPLE_ITEM_TAG);

        // You can also add normal item tags here
    }

}
```

Like all data providers, the copying tag provider must be added to `GatherDataEvent`s:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createBlockAndItemTags(MyBlockTagsProvider::new, ExampleBlockTagCopyingItemTagProvider::new);
}
```

[damagetype]: damagetypes.md
[datacomponent]: ../../items/datacomponents.md
[datagen]: ../index.md#data-generation
[registry]: ../../concepts/registries.md
[regkey]: ../../misc/identifier.md#resourcekeys
[identifier]: ../../misc/identifier.md