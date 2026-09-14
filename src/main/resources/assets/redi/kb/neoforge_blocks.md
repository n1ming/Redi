# NeoForge 方块与方块实体

> 来源:neoforged/Documentation 官方文档(英文原文,API 名与代码签名原样保留)。
> 回答时用中文解释,类名/方法名保持英文。

## blockentities/ber

# BlockEntityRenderer

A `BlockEntityRenderer`, often abbreviated as BER, is used to 'render' [blocks][block] in a way that cannot be represented with a [static baked model][model] (JSON, OBJ, others). For example, this could be used to dynamically render container contents of a chest-like block. A block entity renderer requires the block to have a [`BlockEntity`][blockentity], even if the block does not store any data otherwise.

BERs directly implements the `BlockEntityRenderer`, which submits its [features] for rendering:

```java
// The generic type in the superinterface should be set to what block entity
// you are trying to render, along with its extracted render state. More on this below.
public class MyBlockEntityRenderer implements BlockEntityRenderer<MyBlockEntity, MyBlockEntityRenderState> {

    public MyBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        // Get whatever is necessary from the context
    }

    // Tell the renderer how to create a new render state.
    @Override
    public MyBlockEntityRenderState createRenderState() {
        return new MyBlockEntityRenderState();
    }

    // Update the render state by copying the needed values from the passed block entity
    // to the passed render state.
    // The block entity and render state are the generic types passed to the renderer
    @Override
    public void extractRenderState(MyBlockEntity blockEntity, MyBlockEntityRenderState renderState, float partialTick, Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        // Always call super or `BlockEntityRenderState#extractBase`
        super.extractRenderState(blockEntity, renderState, partialTick, cameraPos, crumblingOverlay);

        // Extract and store any additional values in the state here.
        renderState.value = blockEntity.getValue();
    }

    // Actually submit the features of the block entity to render.
    // The first parameter matches the render state's generic type.
    @Override
    public void submit(MyBlockEntityRenderState renderState, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState) {
        // Submit using the collector here.
    }
}
```

Now that we have our BER, we also need to register and connect it to its owning block entity. This is done in [`EntityRenderersEvent.RegisterRenderers`][event] like so:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
    event.registerBlockEntityRenderer(
            // The block entity type to register the renderer for.
            MyBlockEntities.MY_BLOCK_ENTITY.get(),
            // A function of BlockEntityRendererProvider.Context to BlockEntityRenderer.
            MyBlockEntityRenderer::new
    );
}
```

:::note

In the event that you do not need the provider context in your BER, you can also remove the constructor:

```java
public class MyBlockEntityRenderer implements BlockEntityRenderer<MyBlockEntity, MyBlockEntityRenderState> {
    
    // ...
}

// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
    event.registerBlockEntityRenderer(MyBlockEntities.MY_BLOCK_ENTITY.get(),
            // Pass the context to an empty (default) constructor call
            context -> new MyBlockEntityRenderer()
    );
}
```

:::

## Block Entity Render States

As mentioned in the above example, block entity render states are used to extract the values used for rendering from the actual block entity's values. They are functionally mutable data storage objects extended from `BlockEntityRenderState`:

```java
public class MyBlockEntityRenderState extends BlockEntityRenderState {
    public boolean value;
}
```

The values should then be populated from the `BlockEntity` subclass within `BlockEntityRenderer#extractRenderState`.

## Item Block Rendering

As not all block entities with renderers can be represented by static item models, a special renderer can be created to more dynamically control the process. This is done using [`SpecialModelRenderer`s][special]. In these cases, both a special model renderer must be created to submit the desired [features], and a corresponding registered special block model renderer for scenarios when the block itself is being submitted for rendering rather than an item variant (e.g., enderman carrying a block).

Please refer to the [client item documentation][special] for more information.

[block]: ../blocks/index.md
[blockentity]: index.md
[event]: ../concepts/events.md#registering-an-event-handler
[features]: ../rendering/feature.md
[item]: ../items/index.md
[model]: ../resources/client/models/index.md
[special]: ../resources/client/models/items.md#special-models

## blockentities/index

# Block Entities

Block entities allow the storage of data on [blocks][block] in cases where [block states][blockstate] are not suitable. This is especially the case for data with a non-finite amount of options, such as inventories. Block entities are stationary and bound to a block, but otherwise share many similarities with [entities], hence the name.

:::note
If you have a finite and reasonably small amount (= a few hundred at most) of possible states for your block, you might want to consider using [block states][blockstate] instead.
:::

## Creating and Registering Block Entities

Like entities and unlike blocks, the `BlockEntity` class represents the block entity instance, not the [registered][registration] singleton object. The singleton is expressed through the `BlockEntityType<?>` class instead. We will need both to create a new block entity.

Let's begin by creating our block entity class:

```java
public class MyBlockEntity extends BlockEntity {
    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
}
```

As you may have noticed, we pass an undefined variable `type` to the super constructor. Let's leave that undefined variable there for a moment and instead move to registration.

[Registration][registration] happens in a similar fashion to entities. We create an instance of the associated singleton class `BlockEntityType<?>` and register it to the block entity type registry, like so:

```java
public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ExampleMod.MOD_ID);

public static final Supplier<BlockEntityType<MyBlockEntity>> MY_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
        "my_block_entity",
        // The block entity type.
        () -> new BlockEntityType<>(
                // The supplier to use for constructing the block entity instances.
                MyBlockEntity::new,
                // An optional value that, when true, only allows players with OP permissions
                // to load NBT data (e.g. placing a block item)
                false,
                // A vararg of blocks that can have this block entity.
                // This assumes the existence of the referenced blocks as DeferredBlock<Block>s.
                MyBlocks.MY_BLOCK_1.get(), MyBlocks.MY_BLOCK_2.get()
        )
);
```

:::note
Remember that the `DeferredRegister` must be registered to the [mod event bus][modbus]!
:::

Now that we have our block entity type, we can use it in place of the `type` variable we left earlier:

```java
public class MyBlockEntity extends BlockEntity {
    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(MY_BLOCK_ENTITY.get(), pos, state);
    }
}
```

:::info
The reason for this rather confusing setup process is that `BlockEntityType` expects a `BlockEntityType.BlockEntitySupplier<T extends BlockEntity>`, which is basically a `BiFunction<BlockPos, BlockState, T extends BlockEntity>`. As such, having a constructor we can directly reference using `::new` is highly beneficial. However, we also need to provide the constructed block entity type to the default and only constructor of `BlockEntity`, so we need to pass references around a bit.
:::

Finally, we need to modify the block class associated with the block entity. This means that we will not be able to attach block entities to simple instances of `Block`, instead, we need a subclass:

```java
// The important part is implementing the EntityBlock interface and overriding the #newBlockEntity method.
public class MyEntityBlock extends Block implements EntityBlock {
    // Constructor deferring to super.
    public MyEntityBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    // Return a new instance of our block entity here.
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MyBlockEntity(pos, state);
    }
}
```

And then, you of course need to use this class as the type in your [block registration][blockreg]:

```java
public static final DeferredBlock<MyEntityBlock> MY_BLOCK_1 =
        BLOCKS.register("my_block_1", () -> new MyEntityBlock( /* ... */ ));
public static final DeferredBlock<MyEntityBlock> MY_BLOCK_2 =
        BLOCKS.register("my_block_2", () -> new MyEntityBlock( /* ... */ ));
```

## Storing Data

One of the main purposes of `BlockEntity`s is to store data. Data storage on block entities can happen in two ways: reading and writing to a [value I/O][valueio], or using [data attachments][dataattachments]. This section will cover reading and writing to value I/O; for data attachments, please refer to the linked article.

:::info
The main purpose of data attachments is, as the name suggests, attaching data to existing block entities, such as those provided by vanilla or other mods. For your own mod's block entities, saving and loading directly to and from the value I/O is preferred.
:::

Data can be read from and written to [value I/O][valueio] using the `#loadAdditional` and `#saveAdditional` methods, respectively. These methods are called when the block entity is synced to disk or over the network.

```java
public class MyBlockEntity extends BlockEntity {
    // This can be any value of any type you want, so long as you can somehow serialize it to the value I/O.
    // We will use an int for the sake of example.
    private int value;

    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(MY_BLOCK_ENTITY.get(), pos, state);
    }

    // Read values from the passed ValueInput here.
    @Override
    public void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // Will default to 0 if absent. See the ValueIO article for more information.
        this.value = input.getIntOr("value", 0);
    }

    // Save values into the passed ValueOutput here.
    @Override
    public void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("value", this.value);
    }
}
```

In both methods, it is important that you call super, as that adds basic information such as the position. The tag names `id`, `x`, `y`, `z`, `NeoForgeData` and `neoforge:attachments` are reserved by the super methods, and as such, you should not use them yourself.

Of course, you will want to set other values and not just work with defaults. You can do so freely, like with any other field. However, if you want the game to save those changes, you must call `#setChanged()` afterward, which marks the block entity's chunk as dirty (= in need of being saved). If you do not call that method, the block entity might get skipped during saving, as Minecraft's saving system only saves chunks that have been marked as dirty.

### Removing Block Entities

Sometimes, you may want the block entity to export its stored data on removal (e.g., dropping its inventory when broken by the player). In these instances, the logic should be handled within `BlockEntity#preRemoveSideEffects`. By default, if your block entity drops implements [`Container`][container], then the block entity will drop its stored contents.

```java
public class MyBlockEntity extends BlockEntity {

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        // Perform any remaining export logic on removal here.
    }
}
```

:::warning
Blocks that are removed with the `Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS` flag set will not call this method. This is commonly the case when using the clone commands or if a structure is placed in strict mode.
:::

If neighboring blocks need to know about the block entity breaking (e.g., inventory outputing a redstone signal through a comparator), then your block should override `BlockBehaviour#affectNeighborsAfterRemoval`. Block entities which output a redstone signal typically call `Containers#updateNeighboursAfterDestroy` here.

```java
public class MyEntityBlock extends Block implements EntityBlock {

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        // Handle whatever logic you want to execute on the surrounding neighbors
        Containers.updateNeighboursAfterDestroy(state, level, pos);
    }
}
```

## Tickers

Another very common use of block entities, often in combination with some stored data, is ticking. Ticking means executing some code every game tick. This is done by overriding `EntityBlock#getTicker` and returning a `BlockEntityTicker`, which is basically a consumer with four arguments (level, position, blockstate and block entity), like so:

```java
// Note: The ticker is defined in the block, not the block entity. However, it is good practice to
// keep the ticking logic in the block entity in some way, for example by defining a static #tick method.
public class MyEntityBlock extends Block implements EntityBlock {
    // other stuff here

    // We use a second method here due to generic conversions
    // If extending `BaseEntityBlock`, this method is also available there as a protected static method
    private static <E extends BlockEntity, A extends BlockEntity> @Nullable BlockEntityTicker<A> createTickerHelper(
        BlockEntityType<A> type, BlockEntityType<E> checkedType, BlockEntityTicker<? super E> ticker
    ) {
        return checkedType == type ? (BlockEntityTicker<A>) ticker : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // You can return different tickers here, depending on whatever factors you want. A common use case would be
        // to return different tickers on the client or server, only tick one side to begin with,
        // or only return a ticker for some blockstates (e.g. when using a "my machine is working" blockstate property).
        return createTickerHelper(type, MY_BLOCK_ENTITY.get(), MyBlockEntity::tick);
    }
}

public class MyBlockEntity extends BlockEntity {
    // other stuff here

    // The signature of this method matches the signature of the BlockEntityTicker functional interface.
    public static void tick(Level level, BlockPos pos, BlockState state, MyBlockEntity blockEntity) {
        // Whatever you want to do during ticking.
        // For example, you could change a crafting progress value or consume power here.
    }
}
```

Be aware that the `#tick` method is actually called every tick. Due to this, you should avoid doing a lot of complex calculations in here if you can, for example by only calculating things every X ticks, or by caching the results.

## Syncing

Block entity logic is usually run on the server. As such, we need to tell the client what we are doing. There are three ways to do just that: on chunk load, on block update, or by using a custom packet. You should generally only sync information when it is necessary, to not needlessly clog up the network. 

### Syncing on Chunk Load

A chunk is loaded (and by extension, this method is utilized) each time it is read from either network or disk. To send your data here, you need to override the following methods:

```java
public class MyBlockEntity extends BlockEntity {
    // ...

    // Create an update tag here. For block entities with only a few fields, this can just call #saveWithoutMetadata.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    // Handle a received update tag here. The default implementation calls #loadWithComponents here,
    // so you do not need to override this method if you don't plan to do anything beyond that.
    @Override
    public void handleUpdateTag(ValueInput input) {
        super.handleUpdateTag(input);
    }
}
```

### Syncing on Block Update

This method is used whenever a block update occurs. Block updates must be triggered manually, but are generally processed faster than chunk syncing.

```java
public class MyBlockEntity extends BlockEntity {
    // ...

    // Create an update tag here, like above.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    // Return our packet here. This method returning a non-null result tells the game to use this packet for syncing.
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        // The packet uses the CompoundTag returned by #getUpdateTag. An alternative overload of #create exists
        // that allows you to specify a custom update tag, including the ability to omit data the client might not need.
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // Optionally: Run some custom logic when the packet is received.
    // The super/default implementation forwards to #loadWithComponents.
    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        super.onDataPacket(connection, input);
        // Do whatever you need to do here.
    }
}
```

To actually send the packet, an update notification must be triggered on the server by calling `Level#sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags)`. The position should be the block entity's position, obtainable via `BlockEntity#getBlockPos`. Both blockstate parameters can be the blockstate at the block entity's position, obtainable via `BlockEntity#getBlockState`. Finally, the `flags` parameter is an update mask, as used in [`Level#setBlock`][setblock].

### Using a Custom Packet

By using a dedicated update packet, you can send packets yourself whenever you need to. This is the most versatile, but also the most complex variant, as it requires setting up a network handler. You can send a packet to all players tracking the block entity by using `PacketDistrubtor#sendToPlayersTrackingChunk`. Please see the [Networking][networking] section for more information.

:::caution
It is important that you do safety checks, as the `BlockEntity` might already be destroyed/replaced when the message arrives at the player. You should also check if the chunk is loaded via `Level#hasChunkAt`.
:::

[block]: ../blocks/index.md
[blockreg]: ../blocks/index.md#basic-blocks
[blockstate]: ../blocks/states.md
[container]: ../inventories/container.md
[dataattachments]: ../datastorage/attachments.md
[entities]: ../entities/index.md
[modbus]: ../concepts/events.md#event-buses
[networking]: ../networking/index.md
[registration]: ../concepts/registries.md#methods-for-registering
[setblock]: ../blocks/states.md#levelsetblock
[valueio]: ../datastorage/valueio.md

## blocks/index

# Blocks

Blocks are essential to the Minecraft world. They make up all the terrain, structures, and machines. Chances are if you are interested in making a mod, then you will want to add some blocks. This page will guide you through the creation of blocks, and some of the things you can do with them.

## One Block to Rule Them All

Before we get started, it is important to understand that there is only ever one of each block in the game. A world consists of thousands of references to that one block in different locations. In other words, the same block is just displayed a lot of times.

Due to this, a block should only ever be instantiated once, and that is during [registration]. Once the block is registered, you can then use the registered reference as needed.

Unlike most other registries, blocks can use a specialized version of `DeferredRegister`, called `DeferredRegister.Blocks`. `DeferredRegister.Blocks` acts basically like a `DeferredRegister<Block>`, but with some minor differences:

- They are created via `DeferredRegister.createBlocks("yourmodid")` instead of the regular `DeferredRegister.create(...)` method.
- `#register` returns a `DeferredBlock<T extends Block>`, which extends `DeferredHolder<Block, T>`. `T` is the type of the class of the block we are registering.
- There are a few helper methods for registering block. See [below] for more details.

So now, let's register our blocks:

```java
//BLOCKS is a DeferredRegister.Blocks
public static final DeferredBlock<Block> MY_BLOCK = BLOCKS.register("my_block", registryName -> new Block(...));
```

After registering the block, all references to the new `my_block` should use this constant. For example, if you want to check if the block at a given position is `my_block`, the code for that would look something like this:

```java
level.getBlockState(position) // returns the blockstate placed in the given level (world) at the given position
    //highlight-next-line
    .is(MyBlockRegistrationClass.MY_BLOCK);
```

This approach also has the convenient effect that `block1 == block2` works and can be used instead of Java's `equals` method (using `equals` still works, of course, but is pointless since it compares by reference anyway).

:::danger
Do not call `new Block()` outside registration! As soon as you do that, things can and will break:

- Blocks must be created while registries are unfrozen. NeoForge unfreezes registries for you and freezes them later, so registration is your time window to create blocks.
- If you try to create and/or register a block when registries are frozen again, the game will crash and report a `null` block, which can be very confusing.
- If you still manage to have a dangling block instance, the game will not recognize it while syncing and saving, and replace it with air.
:::

## Creating Blocks

As discussed before, we start by creating our `DeferredRegister.Blocks`:

```java
public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("yourmodid");
```

### Basic Blocks

For simple blocks which need no special functionality (think cobblestone, wooden planks, etc.), the `Block` class can be used directly. To do so, during registration, instantiate `Block` with a `BlockBehaviour.Properties` parameter. This `BlockBehaviour.Properties` parameter can be created using `BlockBehaviour.Properties#of`, and it can be customized by calling its methods. The most important methods for this are:

- `setId` - Sets the resource key of the block.
    - This **must** be set on every block; otherwise, an exception will be thrown.
- `destroyTime` - Determines the time the block needs to be destroyed.
    - Stone has a destroy time of 1.5, dirt has 0.5, obsidian has 50, and bedrock has -1 (unbreakable).
- `explosionResistance` - Determines the explosion resistance of the block.
    - Stone has an explosion resistance of 6.0, dirt has 0.5, obsidian has 1,200, and bedrock has 3,600,000.
- `sound` - Sets the sound the block makes when it is punched, broken, or placed.
    - The default value is `SoundType.STONE`. See the [Sounds page][sounds] for more details.
- `lightLevel` - Sets the light emission of the block. Accepts a function with a `BlockState` parameter that returns a value between 0 and 15.
    - For example, glowstone uses `state -> 15`, and torches use `state -> 14`.
- `friction` - Sets the friction (slipperiness) of the block.
    - Default value is 0.6. Ice uses 0.98.

So for example, a simple implementation would look something like this:

```java
//BLOCKS is a DeferredRegister.Blocks
public static final DeferredBlock<Block> MY_BETTER_BLOCK = BLOCKS.register(
    "my_better_block", 
    registryName -> new Block(BlockBehaviour.Properties.of()
        //highlight-start
        .setId(ResourceKey.create(Registries.BLOCK, registryName))
        .destroyTime(2.0f)
        .explosionResistance(10.0f)
        .sound(SoundType.GRAVEL)
        .lightLevel(state -> 7)
        //highlight-end
    ));
```

For further documentation, see the source code of `BlockBehaviour.Properties`. For more examples, or to look at the values used by Minecraft, have a look at the `Blocks` class.

:::note
It is important to understand that a block in the world is not the same thing as in an inventory. What looks like a block in an inventory is actually a `BlockItem`, a special type of [item] that places a block when used. This also means that things like the creative tab or the max stack size are handled by the corresponding `BlockItem`.

A `BlockItem` must be registered separately from the block. This is because a block does not necessarily need an item, for example if it is not meant to be collected (as is the case with fire, for example).
:::

### More Functionality

Directly using `Block` only allows for very basic blocks. If you want to add functionality, like player interaction or a different hitbox, a custom class that extends `Block` is required. The `Block` class has many methods that can be overridden to do different things; see the classes `Block`, `BlockBehaviour` and `IBlockExtension` for more information. See also the [Using blocks][usingblocks] section below for some of the most common use cases for blocks.

If you want to make a block that has different variants (think a slab that has a bottom, top, and double variant), you should use [blockstates]. And finally, if you want a block that stores additional data (think a chest that stores its inventory), a [block entity][blockentities] should be used. The rule of thumb here is that if you have a finite and reasonably small amount of states (= a few hundred states at most), use blockstates, and if you have an infinite or near-infinite amount of states, use a block entity.

#### Block Types

Block types are [`MapCodec`s][codec] used to serialize and deserialize a block object. This `MapCodec` is set via `BlockBehaviour#codec` and [registered][registration] to the block type registry. Currently, its only use is when the block list report is being generated. A block type should be created once for every subclass of `Block`. For example, `FlowerBlock#CODEC` represents the block type for most flowers while its subclass `WitherRoseBlock` has a separate block type.

If the block subclass only takes in the `BlockBehaviour.Properties`, then `BlockBehaviour#simpleCodec` can be used to create the `MapCodec`.

```java
// For some block subclass
public class SimpleBlock extends Block {
    public SimpleBlock(BlockBehavior.Properties properties) {
        // ...
    }

    @Override
    public MapCodec<SimpleBlock> codec() {
        return SIMPLE_CODEC.get();
    }
}

// In some registration class
public static final DeferredRegister<MapCodec<? extends Block>> REGISTRAR = DeferredRegister.create(BuiltInRegistries.BLOCK_TYPE, "yourmodid");

public static final Supplier<MapCodec<SimpleBlock>> SIMPLE_CODEC = REGISTRAR.register(
    "simple",
    () -> BlockBehaviour.simpleCodec(SimpleBlock::new)
);
```

If the block subclass contains more parameters, then [`RecordCodecBuilder#mapCodec`][codec] should be used to create the `MapCodec`, passing in `BlockBehaviour#propertiesCodec` for the `BlockBehaviour.Properties` parameter.

```java
// For some block subclass
public class ComplexBlock extends Block {
    public ComplexBlock(int value, BlockBehavior.Properties properties) {
        // ...
    }

    @Override
    public MapCodec<ComplexBlock> codec() {
        return COMPLEX_CODEC.get();
    }

    public int getValue() {
        return this.value;
    }
}

// In some registration class
public static final DeferredRegister<MapCodec<? extends Block>> REGISTRAR = DeferredRegister.create(BuiltInRegistries.BLOCK_TYPE, "yourmodid");

public static final Supplier<MapCodec<ComplexBlock>> COMPLEX_CODEC = REGISTRAR.register(
    "simple",
    () -> RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            Codec.INT.fieldOf("value").forGetter(ComplexBlock::getValue),
            BlockBehaviour.propertiesCodec() // represents the BlockBehavior.Properties parameter
        ).apply(instance, ComplexBlock::new)
    )
);
```

:::note
Although block types are basically unused at the moment, it is expected to become more important in the future as Mojang continues moving towards a codec-centered structure.
:::

### `DeferredRegister.Blocks` helpers

We already discussed how to create a `DeferredRegister.Blocks` [above], as well as that it returns `DeferredBlock`s. Now, let's have a look at what other utilities the specialized `DeferredRegister` has to offer. Let's start with `#registerBlock`:

```java
public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("yourmodid");

public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.register(
    "example_block", registryName -> new Block(
        BlockBehaviour.Properties.of()
            // The ID must be set on the block
            .setId(ResourceKey.create(Registries.BLOCK, registryName))
    )
);

// Same as above, except that the block properties are supplied separately.
// setId is also called internally on the properties object.
public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerBlock(
    "example_block",
    Block::new, // The factory that the properties will be passed into.
    () -> BlockBehaviour.Properties.of() // The supplied properties to use.
);

// Same as above, except that the `Properties#of` is supplied and operated upon.
// setId is also called internally on the properties object.
public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerBlock(
    "example_block",
    Block::new, // The factory that the properties will be passed into.
    props -> props // A unary operator of the properties to use.
);
```

If you want to use `Block::new`, you can leave out the factory entirely:

```java
public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock(
    "example_block",
    () -> BlockBehaviour.Properties.of() // The supplied properties to use.
);

public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock(
    "example_block",
    props -> props // A unary operator of the properties to use.
);
```

This does the exact same as the previous example, but is slightly shorter. Of course, if you want to use a subclass of `Block` and not `Block` itself, you will have to use the previous method instead.

### Resources

If you register your block and place it in the world, you will find it to be missing things like a texture. This is because [textures], among others, are handled by Minecraft's resource system. When adding a new block in Minecraft, you should either write or [generate][datagen] the following files:

- A [blockstate file][bsfile]
- A [block model][model]
- A [translation][i18n]
- A [loot table][loottable]
- Some block [tags], e.g. for mining

For all of the above, also reference the files and data generators of similar vanilla blocks.

## Using Blocks

Blocks are very rarely directly used to do things. In fact, probably two of the most common operations in all of Minecraft - getting the block at a position, and setting a block at a position - use blockstates, not blocks. The general design approach is to have the block define behavior, but have the behavior actually run through blockstates. Due to this, `BlockState`s are often passed to methods of `Block` as a parameter. For more information on how blockstates are used, and on how to get one from a block, see [Using Blockstates][usingblockstates].

In several situations, multiple methods of `Block` are used at different times. The following subsections list the most common block-related pipelines. Unless specified otherwise, all methods are called on both logical sides and should return the same result on both sides.

### Placing a Block

Block placement logic is called from `BlockItem#useOn` (or some subclass's implementation thereof, such as in `PlaceOnWaterBlockItem`, which is used for lily pads). For more information on how the game gets there, see [Right-Clicking Items][rightclick]. In practice, this means that as soon as a `BlockItem` is right-clicked (for example a cobblestone item), this behavior is called.

- Several prerequisites are checked, for example that you are not in spectator mode, that all required feature flags for the block are enabled or that the target position is not outside the world border. If at least one of these checks fails, the pipeline ends.
- `BlockBehaviour#canBeReplaced` is called for the block currently at the position where the block is attempted to be placed. If it returns `false`, the pipeline ends. Prominent cases that return `true` here are tall grass or snow layers.
- `Block#getStateForPlacement` is called. This is where, depending on the context (which includes information like the position, the rotation and the side the block is placed on), different block states can be returned. This is useful for example for blocks that can be placed in different directions.
- `BlockBehaviour#canSurvive` is called with the blockstate obtained in the previous step. If it returns `false`, the pipeline ends.
- The blockstate is set into the level via a `Level#setBlock` call.
    - In that `Level#setBlock` call, `BlockBehaviour#onPlace` is called.
- `Block#setPlacedBy` is called.

### Breaking a Block

Breaking a block is a bit more complex, as it requires time. The process can be roughly divided into three stages: "initiating", "mining" and "actually breaking".

- When the left mouse button is clicked, the "initiating" stage is entered. 
- Now, the left mouse button needs to be held down, entering the "mining" stage. **This stage's methods are called every tick.**
- If the "continuing" stage is not interrupted (by releasing the left mouse button) and the block is broken, the "actually breaking" stage is entered.

Or for those who prefer pseudocode:

```java
leftClick();
initiatingStage();
while (leftClickIsBeingHeld()) {
    miningStage();
    if (blockIsBroken()) {
        actuallyBreakingStage();
        break;
    }
}
```

The following subsections further break down these stages into actual method calls. For information about how the game gets from left-clicking to this pipeline, see [Left-Clicking an Item][leftclick].

#### The "Initiating" Stage

- Several prerequisites are checked, for example that you are not in spectator mode, that all required feature flags for the `ItemStack` in your main hand are enabled or that the block in question is not outside the world border. If at least one of these checks fails, the pipeline ends.
- `PlayerInteractEvent.LeftClickBlock` is fired. If the event is canceled, the pipeline ends.
    - Note that when the event is canceled on the client, no packets are sent to the server and thus no logic runs on the server.
    - However, canceling this event on the server will still cause client code to run, which can lead to desyncs!
- `BlockBehaviour#attack` is called.

#### The "Mining" Stage

- `PlayerInteractEvent.LeftClickBlock` is fired. If the event is canceled, the pipeline moves to the "finishing" stage.
    - Note that when the event is canceled on the client, no packets are sent to the server and thus no logic runs on the server.
    - However, canceling this event on the server will still cause client code to run, which can lead to desyncs!
- `BlockBehaviour#getDestroyProgress` is called and added to the internal destroy progress counter.
    - `BlockBehaviour#getDestroyProgress` returns a float value between 0 and 1, representing how much the destroy progress counter should be increased every tick.
- The progress overlay (cracking texture) is updated accordingly.
- If the destroy progress is greater than 1.0 (i.e. completed, i.e. the block should be broken), the "mining" stage is exited and the "actually breaking" stage is entered.

#### The "Actually Breaking" Stage

- `Item#canDestroyBlock` is called. If it returns `false` (determining that the block should not be broken), the pipeline moves to the "finishing" stage.
- `Player#canUseGameMasterBlocks` is called if the block is an instance of `GameMasterBlock`. This determines whether the player has the ability to destroy creative-only blocks. If `false`, the pipeline moves to the "finishing" stage.
- Server-only: `Player#blockActionRestricted` is called. This determines whether the current player cannot break the block. If `true`, the pipeline moves to the "finishing" stage.
- Server-only: `BlockEvent.BreakEvent` is fired. If canceled, the pipeline moves to the "finishing" stage. The initial canceled state is determined by the above three methods.
- `Block#playerWillDestroy` is called.
- Server-only: `IBlockExtension#canHarvestBlock` is called. This determines whether the block can be harvested, i.e. broken with drops. This will be ignored if `Player#preventsBlockDrops` returns true.
    - Server-only: `PlayerEvent.HarvestCheck` is fired if `IBlockExtension#canHarvestBlock` is not overridden without its super call. If `HarvestCheck#canHarvest` returns `false`, then `Block#playerDestroy` will not be called, preventing any resources or experience from dropping.
- Server-only: `Item#mineBlock` is called.
- `IBlockExtension#onDestroyedByPlayer` is called. If it returns `false`, the pipeline moves to the "finishing" stage.
    - The blockstate is removed from the level via a `Level#setBlock` call with `Blocks.AIR.defaultBlockState()` or the current logged fluid as the blockstate parameter.
        - In that `Level#setBlock` call, `Block#onRemove` is called.
    - `Block#destroy` is called if `IBlockExtension#onDestroyedByPlayer` returns `true`.
- Server-only: If the previous call to `IBlockExtension#canHarvestBlock` and `IBlockExtension#onDestroyedByPlayer` return `true`, then `Block#playerDestroy` is called.
    - Server-only: `Block#dropResources` is called. This determines what drops from the block when mined, including experience.
        - Server-only: `BlockDropsEvent` is fired. If the event is canceled, then nothing is dropped when the block breaks. Otherwise, every `ItemEntity` in `BlockDropsEvent#getDrops` is added to the current level. Additionally, `Block#popExperience` is called if `getDroppedExperience` is greater than 0.
            - Server-only: `IBlockExtension#getExpDrop` is called, augmented by `EnchantmentHelper#processBlockExperience`. This is the initial value set for `BlockDropsEvent#getDroppedExperience` before potentally being modified.
- Server-only: `PlayerDestroyItemEvent` is fired if the item used to mine the block broke at any point in the above process.

#### Mining Speed

The mining speed is calculated from the block's hardness, the used [tool]'s speed, and several entity [attributes] according to the following rules:

```java
// This will return the tool's mining speed, or 1 if the held item is either empty, not a tool,
// or not applicable for the block being broken.
float destroySpeed = item.getDestroySpeed(blockState);
// If we have an applicable tool, add the minecraft:mining_efficiency attribute as an additive modifier.
if (destroySpeed > 1) {
    destroySpeed += player.getAttributeValue(Attributes.MINING_EFFICIENCY);
}
// Apply effects from haste or conduit power.
if (player.hasEffect(MobEffects.HASTE) || player.hasEffect(MobEffects.CONDUIT_POWER)) {
    int haste = player.hasEffect(MobEffects.HASTE)
        ? player.getEffect(MobEffects.HASTE).getAmplifier()
        : 0;
    int conduitPower = player.hasEffect(MobEffects.CONDUIT_POWER)
        ? player.getEffect(MobEffects.CONDUIT_POWER).getAmplifier()
        : 0;
    int amplifier = Math.max(haste, conduitPower);
    destroySpeed *= 1 + (amplifier + 1) * 0.2f;
}
// Apply slowness effect.
if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
    destroySpeed *= switch (player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
        case 0 -> 0.3F;
        case 1 -> 0.09F;
        case 2 -> 0.0027F;
        default -> 8.1E-4F;
    };
}
// Add the minecraft:block_break_speed attribute as a multiplicative modifier.
destroySpeed *= player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
// If the player is underwater, apply the underwater mining speed penalty multiplicatively.
if (player.isEyeInFluid(FluidTags.WATER)) {
    destroySpeed *= player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
}
// If the player is trying to break a block in mid-air, make the player mine 5 times slower.
if (!player.onGround()) {
    destroySpeed /= 5;
}
destroySpeed = /* The PlayerEvent.BreakSpeed event is fired here, allowing modders to further modify this value. */;
return destroySpeed;
```

The exact code for this can be found in `Player#getDestroySpeed` for reference.

### Ticking

Ticking is a mechanism that updates (ticks) parts of the game every 1 / 20 seconds, or 50 milliseconds ("one tick"). Blocks provide different ticking methods that are called in different ways.

#### Server Ticking and Tick Scheduling

`BlockBehaviour#tick` is called through scheduled ticks. Scheduled ticks can be created through `Level#scheduleTick(BlockPos, Block, int)`, where the `int` denotes a delay. This is used in various places by vanilla, for example, the tilting mechanism of big dripleaves heavily relies on this system. Other prominent users are various redstone components.

#### Client Ticking

`Block#animateTick` is called exclusively on the client, every frame. This is where client-only behavior, for example the torch particle spawning, happens.

#### Weather Ticking

Weather ticking is handled by `Block#handlePrecipitation` and runs independent of regular ticking. It is called only on the server, only when it is raining in some form, with a 1 in 16 chance. This is used for example by cauldrons that fill during rain or snowfall.

#### Random Ticking

The random tick system runs independent of regular ticking. Random ticks must be enabled through the `BlockBehaviour.Properties` of the block by calling the `BlockBehaviour.Properties#randomTicks()` method. This enables the block to be part of the random ticking mechanic.

Random ticks occur every tick for a set amount of blocks in a chunk. That set amount is defined through the `randomTickSpeed` gamerule. With its default value of 3, every tick, 3 random blocks from the chunk are chosen. If these blocks have random ticking enabled, then their respective `BlockBehaviour#randomTick` methods are called.

Random ticking is used by a wide range of mechanics in Minecraft, such as plant growth, ice and snow melting, or copper oxidizing.

[above]: #one-block-to-rule-them-all
[attributes]: ../entities/attributes.md
[below]: #deferredregisterblocks-helpers
[blockentities]: ../blockentities/index.md
[blockstates]: states.md
[bsfile]: ../resources/client/models/index.md#blockstate-files
[codec]: ../datastorage/codecs.md#records
[datagen]: ../resources/index.md#data-generation
[i18n]: ../resources/client/i18n.md
[item]: ../items/index.md
[leftclick]: ../items/interactions.md#left-clicking-an-item
[loottable]: ../resources/server/loottables/index.md
[model]: ../resources/client/models/index.md
[registration]: ../concepts/registries.md#methods-for-registering
[resources]: ../resources/index.md#assets
[rightclick]: ../items/interactions.md#right-clicking-an-item
[sounds]: ../resources/client/sounds.md
[tags]: ../resources/server/tags.md
[textures]: ../resources/client/textures.md
[tool]: ../items/tools.md
[usingblocks]: #using-blocks
[usingblockstates]: states.md#using-blockstates

## blocks/states

# Blockstates

Often, you will find yourself in a situation where you want different states of a block. For example, a wheat crop has eight growth stages, and making a separate block for each stage feels wrong. Or you have a slab or slab-like block - one bottom state, one top state, and one state that has both.

This is where blockstates come into play. Blockstates are an easy way to represent the different states a block can have, like a growth stage or a slab placement type.

## Blockstate Properties

Blockstates use a system of properties. A block can have multiple properties of multiple types. For example, an end portal frame has two properties: whether it has an eye (`eye`, 2 options) and which direction it is placed in (`facing`, 4 options). So in total, the end portal frame has 8 (2 * 4) different blockstates:

```
minecraft:end_portal_frame[facing=north,eye=false]
minecraft:end_portal_frame[facing=east,eye=false]
minecraft:end_portal_frame[facing=south,eye=false]
minecraft:end_portal_frame[facing=west,eye=false]
minecraft:end_portal_frame[facing=north,eye=true]
minecraft:end_portal_frame[facing=east,eye=true]
minecraft:end_portal_frame[facing=south,eye=true]
minecraft:end_portal_frame[facing=west,eye=true]
```

The notation `blockid[property1=value1,property2=value,...]` is the standardized way of representing a blockstate in text form, and is used in some locations in vanilla, for example in commands.

If your block does not have any blockstate properties defined, it still has exactly one blockstate - that is the one without any properties, since there are no properties to specify. This can be denoted as `minecraft:oak_planks[]` or simply `minecraft:oak_planks`.

As with blocks, every `BlockState` exists exactly once in memory. This means that `==` can and should be used to compare `BlockState`s. `BlockState` is also a final class, meaning it cannot be extended. **Any functionality goes in the corresponding [Block][block] class!**

## When to Use Blockstates

### Blockstates vs. Separate Blocks

A good rule of thumb is: **if it has a different name, it should be a separate block**. An example is making chair blocks: the direction of the chair should be a property, while the different types of wood should be separated into different blocks. So you'd have one chair block for each wood type, and each chair block has four blockstates (one for each direction).

### Blockstates vs. [Block Entities][blockentity]

Here, the rule of thumb is: **if you have a finite amount of states, use a blockstate, if you have an infinite or near-infinite amount of states, use a block entity.** Block entities can store arbitrary amounts of data, but are slower than blockstates.

Blockstates and block entities can be used in conjunction with one another. For example, the chest uses blockstate properties for things like the direction, whether it is waterlogged or not, or becoming a double chest, while storing the inventory, whether it is currently open or not, or interacting with hoppers is handled by a block entity.

There is no definitive answer to the question "How many states are too much for a blockstate?", but we recommend that if you need more than 8-9 bits of data (i.e. more than a few hundred states), you should use a block entity instead.

## Implementing Blockstates

To implement a blockstate property, in your block class, create or reference a `public static final Property<?>` constant. While you are free to make your own `Property<?>` implementations, the vanilla code provides several convenience implementations that should cover most use cases:

- `IntegerProperty`
    - Implements `Property<Integer>`. Defines a property that holds an integer value. Note that negative values are not supported.
    - Created by calling `IntegerProperty#create(String name, int min, int max)`.
- `BooleanProperty`
    - Implements `Property<Boolean>`. Defines a property that holds a `true` or `false` value.
    - Created by calling `BooleanProperty#create(String name)`.
- `EnumProperty<E extends Enum<E>>`
    - Implements `Property<E>`. Defines a property that can take on the values of an Enum class.
    - Created by calling `EnumProperty#create(String name, Class<E> enumClass)`.
    - It is also possible to use only a subset of the Enum values (e.g. 4 out of 16 `DyeColor`s), see the overloads of `EnumProperty#create`.

The class `BlockStateProperties` contains shared vanilla properties which should be used or referenced whenever possible, in place of creating your own properties.

Once you have your property constant, override `Block#createBlockStateDefinition(StateDefinition.Builder)` in your block class. In that method, call `StateDefinition.Builder#add(YOUR_PROPERTY);`. `StateDefinition.Builder#add` has a vararg parameter, so if you have multiple properties, you can add them all in one go.

Every block will also have a default state. If nothing else is specified, the default state uses the default value of every property. You can change the default state by calling the `Block#registerDefaultState(BlockState)` method from your constructor.

If you wish to change which `BlockState` is used when placing your block, override `Block#getStateForPlacement(BlockPlaceContext)`. This can be used to, for example, set the direction of your block depending on where the player is standing or looking when they place it.

To further illustrate this, this is what the relevant bits of the `EndPortalFrameBlock` class look like:

```java
public class EndPortalFrameBlock extends Block {
    // Note: It is possible to directly use the values in BlockStateProperties instead of referencing them here again.
    // However, for the sake of simplicity and readability, it is recommended to add constants like this.
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty EYE = BlockStateProperties.EYE;

    public EndPortalFrameBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // stateDefinition.any() returns a random BlockState from an internal set,
        // we don't care because we're setting all values ourselves anyway
        this.registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(EYE, false)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // this is where the properties are actually added to the state
        builder.add(FACING, EYE);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // code that determines which state will be used when
        // placing down this block, depending on the BlockPlaceContext
    }
}
```

## Using Blockstates

To go from `Block` to `BlockState`, call `Block#defaultBlockState()`. The default blockstate can be changed through `Block#registerDefaultState`, as described above.

You can get the value of a property by calling `BlockState#getValue(Property<?>)`, passing it the property you want to get the value of. Reusing our end portal frame example, this would look something like this:

```java
// EndPortalFrameBlock.FACING is an EnumPropery<Direction> and thus can be used to obtain a Direction from the BlockState
Direction direction = endPortalFrameBlockState.getValue(EndPortalFrameBlock.FACING);
```

If you want to get a `BlockState` with a different set of values, simply call `BlockState#setValue(Property<T>, T)` on an existing block state with the property and its value. With our lever, this goes something like this:

```java
endPortalFrameBlockState = endPortalFrameBlockState.setValue(EndPortalFrameBlock.FACING, Direction.SOUTH);
```

:::note
`BlockState`s are immutable. This means that when you call `#setValue(Property<T>, T)`, you are not actually modifying the blockstate. Instead, a lookup is performed internally, and you are given the blockstate object you requested, which is the one and only object that exists with these exact property values. This also means that just calling `state#setValue` without saving it into a variable (for example back into `state`) does nothing.
:::

To get a `BlockState` from the level, use `Level#getBlockState(BlockPos)`.

### `Level#setBlock`

To set a `BlockState` in the level, use `Level#setBlock(BlockPos, BlockState, int)`.

The `int` parameter deserves some extra explanation, as its meaning is not immediately obvious. It denotes what is known as update flags.

To help setting the update flags correctly, there are a number of `int` constants in `Block`, prefixed with `UPDATE_`. These constants can be bitwise-ORed together (for example `Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS`) if you wish to combine them.

- `Block.UPDATE_NEIGHBORS` sends an update to the neighboring blocks. More specifically, it calls `Block#neighborChanged`, which calls a number of methods, most of which are redstone-related in some way.
- `Block.UPDATE_CLIENTS` syncs the block update to the client.
- `Block.UPDATE_INVISIBLE` explicitly does not update on the client. This also overrules `Block.UPDATE_CLIENTS`, causing the update to not be synced. The block is always updated on the server.
- `Block.UPDATE_IMMEDIATE` forces a re-render on the client's main thread.
- `Block.UPDATE_KNOWN_SHAPE` stops neighbor update recursion.
- `Block.UPDATE_SUPPRESS_DROPS` disables block drops for the old block at that position.
- `Block.UPDATE_MOVE_BY_PISTON` is only used by piston code to signal that the block was moved by a piston. This is mainly responsible for delaying light engine updates.
- `Block.UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE` is used by the `ExperimentalRedstoneWireEvaluator` to signal if the shape should be skipped. This is set only when the power strength is changed from not a placement or that the original source on the signal is not the current wire.
- `Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS` prevents `BlockEntity#preRemoveSideEffects` from being called. This typically prevents block entities from emptying their contents.
- `Block.UPDATE_SKIP_ON_PLACE` prevents `Block#onPlace` from being called. This typically prevents any blocks from handling their initial behavior (e.g. updating rails to attach to other blocks, spawning an golem).
- `Block.UPDATE_NONE` is an alias for `Block.UPDATE_INVISIBLE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`.
- `Block.UPDATE_ALL` is an alias for `Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS`.
- `Block.UPDATE_ALL_IMMEDIATE` is an alias for `Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE`.
- `Block.UPDATE_SKIP_ALL_SIDEEFFECTS` is an alias for `Block.UPDATE_SKIP_ON_PLACE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE`

There is also a convenience method `Level#setBlockAndUpdate(BlockPos pos, BlockState state)` that calls `setBlock(pos, state, Block.UPDATE_ALL)` internally.

[block]: index.md
[blockentity]: ../blockentities/index.md