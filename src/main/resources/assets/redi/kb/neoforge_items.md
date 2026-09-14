# NeoForge 物品与容器

> 来源:neoforged/Documentation 官方文档(英文原文,API 名与代码签名原样保留)。
> 回答时用中文解释,类名/方法名保持英文。

## inventories/capabilities

---
sidebar_position: 1
---
# Capabilities

Capabilities allow exposing features in a dynamic and flexible way without having to resort to directly implementing many interfaces.

In general terms, each capability provides a feature in the form of an interface.

NeoForge adds capability support to blocks, entities, and item stacks. This will be explained in more detail in the following sections.

## Why Use Capabilities?

Capabilities are designed to separate **what** a block, entity or item stack can do from **how** it does it. If you are wondering whether capabilities are the right tool for a job, ask yourself the following questions:

1. Do I only care about **what** a block, entity or item stack can do, but not about **how** it does it?
1. Is the **what**, the behavior, only available for some blocks, entities, or item stacks, but not all of them?
1. Is the **how**, the implementation of that behavior, dependent on the specific block, entity or item stack?

Here are a few examples of good capability usage:

- *"I want my fluid container to be compatible with fluid containers from other mods, but I don't know the specifics of each fluid container."* - Yes, use the `ResourceHandler<FluidResource>` capability.
- *"I want to count how many items are in some entity, but I do not know how the entity might store them."* - Yes, use the `ResourceHandler<ItemResource>` capability.
- *"I want to fill some item stack with power, but I do not know how the item stack might store it."* - Yes, use the `EnergyHandler` capability.
- *"I want to apply some color to whatever block a player is currently targeting, but I do not know how the block will be transformed."* - Yes. NeoForge does not provide a capability to color blocks, but you can implement one yourself.

Here is an example of discouraged capability usage:

- *"I want to check if an entity is within the range of my machine."* - No, use a helper method instead.

## NeoForge-provided capabilities

NeoForge provides capabilities for the following three [resource handlers][resourcehandler]: `ResourceHandler<ItemResource>`, `ResourceHandler<FluidResource>` and `EnergyHandler`.

`ResourceHandler<ItemResource>` exposes an interface for managing inventory slots. The capabilities of type `ResourceHandler<ItemResource>` are:

- `Capabilities.Item.BLOCK`: automation-accessible inventory of a block (for chests, machines, etc).
- `Capabilities.Item.ENTITY`: inventory contents of an entity (extra player slots, mob/creature inventories/bags).
- `Capabilities.Item.ENTITY_AUTOMATION`: automation-accessible inventory of an entity (boats, minecarts, etc).
- `Capabilities.Item.ITEM`: contents of an item stack (portable backpacks and such).

`ResourceHandler<FluidResource>` exposes an interface for managing fluid inventories. The capabilities of type `ResourceHandler<FluidResource>` are:

- `Capabilities.Fluid.BLOCK`: automation-accessible fluid inventory of a block.
- `Capabilities.Fluid.ENTITY`: fluid inventory of an entity.
- `Capabilities.Fluid.ITEM`: fluid inventory of an item stack.

`EnergyHandler` exposes an interface for handling energy containers. It is based on the RedstoneFlux API by TeamCoFH. The capabilities of type [`EnergyHandler`][energyhandler] are:

- `Capabilities.Energy.BLOCK`: energy contained inside a block.
- `Capabilities.Energy.ENTITY`: energy containing inside an entity.
- `Capabilities.Energy.ITEM`: energy contained inside an item stack.

## Creating a capability

NeoForge supports capabilities for blocks, entities, and item stacks.

Capabilities allow looking up implementations of some APIs with some dispatching logic. The following kinds of capabilities are implemented in NeoForge:

- `BlockCapability`: capabilities for blocks and block entities; behavior depends on the specific `Block`.
    - The capability commonly specifies a `Direction` context for different resources depending on the side.
- `EntityCapability`: capabilities for entities: behavior depends on the specific `EntityType`.
    - The capability commonly specifies a `Direction` context for different resources depending on the side.
- `ItemCapability`: capabilities for item stacks: behavior depends on the specific `Item`.
    - The capability commonly specifies an [`ItemAccess`][itemaccess] context for the holding item resource.

:::tip
For compatibility with other mods, we recommend using the capabilities provided by NeoForge in the `Capabilities` class if possible. Otherwise, you can create your own as described in this section.
:::

Creating a capability is a single function call, and the resulting object should be stored in a `static final` field. The following parameters must be provided:

- The name of the capability.
    - Creating a capability with the same name multiple times will always return the same object.
    - Capabilities with different names are **completely independent**, and can be used for different purposes.
- The behavior type that is being queried. This is the `T` type parameter.
- The type for additional context in the query. This is the `C` type parameter.

For example, here is how a capability for side-aware block `ResourceHandler<ItemResource>`s might be declared:

```java
public static final BlockCapability<ResourceHandler<ItemResource>, @Nullable Direction> ITEM_HANDLER_BLOCK =
    BlockCapability.create(
        // Provide a name to uniquely identify the capability.
        Identifier.fromNamespaceAndPath("mymod", "item_handler"),
        // Provide the queried type. Here, we want to look up `ResourceHandler<ItemResource>` instances.
        ResourceHandler.asClass(),
        // Provide the context type. We will allow the query to receive an extra `Direction side` parameter.
        Direction.class
    );
```

A `@Nullable Direction` is so common for blocks that there is a dedicated helper:

```java
public static final BlockCapability<ResourceHandler<ItemResource>, @Nullable Direction> ITEM_HANDLER_BLOCK =
    BlockCapability.createSided(
        // Provide a name to uniquely identify the capability.
        Identifier.fromNamespaceAndPath("mymod", "item_handler"),
        // Provide the queried type. Here, we want to look up `ResourceHandler<ItemResource>` instances.
        ResourceHandler.asClass()
    );
```

If no context is required, `Void` should be used. There is also a dedicated helper for context-less capabilities:

```java
public static final BlockCapability<ResourceHandler<ItemResource>, Void> ITEM_HANDLER_NO_CONTEXT =
    BlockCapability.createVoid(
        // Provide a name to uniquely identify the capability.
        Identifier.fromNamespaceAndPath("mymod", "item_handler_no_context"),
        // Provide the queried type. Here, we want to look up `ResourceHandler<ItemResource>` instances.
        ResourceHandler.asClass()
    );
```

For entities and item stacks, similar methods exist in `EntityCapability` and `ItemCapability` respectively.

## Querying capabilities

Once we have our `BlockCapability`, `EntityCapability`, or `ItemCapability` object in a static field, we can query a capability.

For entities and item stacks, we can try to find implementations of a capability with `getCapability`. If the result is `null`, there no implementation is available.

For example:

```java
var object = entity.getCapability(CAP, context);
if (object != null) {
    // Use object
}
```

```java
var object = stack.getCapability(CAP, context);
if (object != null) {
    // Use object
}
```

Block capabilities are used a bit differently because blocks without a block entity can have capabilities as well. The query is now performed on a `level`, with the `pos`ition that we are looking for as an additional parameter:

```java
var object = level.getCapability(CAP, pos, context);
if (object != null) {
    // Use object
}
```

If the block entity and/or the block state is known, they can be passed to save on query time:

```java
var object = level.getCapability(CAP, pos, blockState, blockEntity, context);
if (object != null) {
    // Use object
}
```

To give a more concrete example, here is how one might query an `ResourceHandler<ItemResource>` capability for a block, from the `Direction.NORTH` side:

```java
ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, Direction.NORTH);
if (handler != null) {
    // Use the handler for some item-related operation.
}
```

## Block capability caching

When a capability is looked up, the system will perform the following steps under the hood:

1. Fetch block entity and block state if they were not supplied.
1. Fetch registered capability providers. (More on this below).
1. Iterate the providers and ask them if they can provide the capability.
1. One of the providers will return a capability instance, potentially allocating a new object.

The implementation is rather efficient, but for queries that are performed frequently, for example every game tick, these steps can take a significant amount of server time. The `BlockCapabilityCache` system provides a dramatic speedup for capabilities that are frequently queried at a given position.

:::tip
Generally, a `BlockCapabilityCache` will be created once and then stored in a field of the object performing frequent capability queries. When and where exactly you store the cache is up to you.
:::

To create a cache, call `BlockCapabilityCache.create` with the capability to query, the level, the position, and the query context.

```java
// Declare the field:
private BlockCapabilityCache<ResourceHandler<ItemResource>, @Nullable Direction> capCache;

// Later, for example in `onLoad` for a block entity:
this.capCache = BlockCapabilityCache.create(
    Capabilities.Item.BLOCK, // capability to cache
    level, // level
    pos, // target position
    Direction.NORTH // context
);
```

Querying the cache is then done with `getCapability()`:

```java
ResourceHandler<ItemResource> handler = this.capCache.getCapability();
if (handler != null) {
    // Use the handler for some item-related operation.
}
```

**The cache is automatically cleared by the garbage collector, there is no need to unregister it.**

It is also possible to receive notifications when the capability object changes! This includes capabilities changing (`oldHandler != newHandler`), becoming unavailable (`null`) or becoming available again (not `null` anymore).

The cache then needs to be created with two additional parameters:

- A validity check, that is used to determine if the cache is still valid.
    - In the simplest usage as a block entity field, `() -> !this.isRemoved()` will do.
- An invalidation listener, that is called when the capability changes.
    - This is where you can react to capability changes, removals, or appearances.

```java
// In `onLoad` for a block entity:
// With optional invalidation listener:
this.capCache = BlockCapabilityCache.create(
    Capabilities.Item.BLOCK, // capability to cache
    level, // level
    pos, // target position
    Direction.NORTH, // context
    () -> !this.isRemoved(), // validity check (because the cache might outlive the object it belongs to)
    () -> onCapInvalidate() // invalidation listener
);
```

## Block capability invalidation

:::info
Invalidation is exclusive to block capabilities. Entity and item stack capabilities cannot be cached and do not need to be invalidated.
:::

To make sure that caches can correctly update their stored capability, **modders must call `level.invalidateCapabilities(pos)` whenever a capability changes, appears, or disappears**.

```java
// whenever a capability changes, appears, or disappears:
level.invalidateCapabilities(pos);
```

NeoForge already handles common cases such as chunk load/unloads and block entity creation/removal, but other cases need to be handled explicitly by modders. For example, modders must invalidate capabilities in the following cases:

- If a previously returned capability is no longer valid.
- If a capability-providing block (without a block entity) is placed or changes state, by overriding `onPlace`.
- If a capability-providing block (without a block entity) is removed, by overriding `onRemove`.

For a plain block example, refer to the `ComposterBlock.java` file.

For more information, refer to the javadoc of [`IBlockCapabilityProvider`][block-cap-provider].

## Registering capabilities

A capability _provider_ is what ultimately supplies a capability. A capability provider is a function that can either return a capability instance, or `null` if it cannot provide the capability. Providers are specific to:

- the given capability that they are providing for, and
- the block instance, block entity type, entity type, or item instance that they are providing for.

They need to be registered in the `RegisterCapabilitiesEvent`.

Block providers are registered with `registerBlock`. For example:

```java
@SubscribeEvent // on the mod event bus
public static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlock(
        Capabilities.Item.BLOCK, // capability to register for
        (level, pos, state, be, side) -> <return the ResourceHandler<ItemResource>>,
        // blocks to register for
        MY_ITEM_HANDLER_BLOCK,
        MY_OTHER_ITEM_HANDLER_BLOCK
    );
}
```

In general, registration will be specific to some block entity types, so the `registerBlockEntity` helper method is provided as well:

```java
event.registerBlockEntity(
    Capabilities.Item.BLOCK, // capability to register for
    MY_BLOCK_ENTITY_TYPE, // block entity type to register for
    (myBlockEntity, side) -> myBlockEntity.myResourceHandlerForTheGivenSide
);
```

:::danger
If the capability previously returned by a block or block entity provider is no longer valid, *you must invalidate the caches** by calling `level.invalidateCapabilities(pos)`. Refer to the [invalidation section][invalidation] above for more information.
:::

Entity registration is similar, using `registerEntity`:

```java
event.registerEntity(
    Capabilities.Item.ENTITY, // capability to register for
    MY_ENTITY_TYPE, // entity type to register for
    (myEntity, v) -> myEntity.myResourceHandlerForTheGivenContext
);
```

Item registration is similar too. Note that the provider receives the stack:

```java
event.registerItem(
    Capabilities.Item.ITEM, // capability to register for
    (stack, itemAccess) -> <return the ResourceHandler<ItemResource> for the itemStack>,
    // items to register for
    MY_ITEM,
    MY_OTHER_ITEM
);
```

## Registering capabilities for all objects

If for some reason you need to register a provider for all blocks, entities, or items, you will need to iterate the corresponding registry and register the provider for each object.

For example, NeoForge uses this system to register a fluid resource handler capability for all `BucketItem`s (excluding subclasses):

```java
// For reference, you can find this code in the `CapabilityHooks` class.
for (Item item : BuiltInRegistries.ITEM) {
    if (item.getClass() == BucketItem.class) {
        event.registerItem(Capabilities.Fluid.ITEM, (stack, itemAccess) -> new BucketResourceHandler(itemAccess), item);
    }
}
```

Providers are asked for a capability in the order that they are registered. Should you want to run before a provider that NeoForge already registers for one of your objects, register your `RegisterCapabilitiesEvent` handler with a higher priority.

For example:

```java
// use HIGH priority to register before NeoForge!
@SubscribeEvent(priority = EventPriority.HIGH) // on the mod event bus
public static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerItem(
        Capabilities.Fluid.ITEM,
        (stack, itemAccess) -> new BucketResourceHandler(itemAccess),
        // Items to register for
        MY_CUSTOM_BUCKET
    );
}
```

See [`CapabilityHooks`][capability-hooks] for a list of the providers registered by NeoForge itself.

[block-cap-provider]: https://github.com/neoforged/NeoForge/blob/26.1.x/src/main/java/net/neoforged/neoforge/capabilities/IBlockCapabilityProvider.java
[capability-hooks]: https://github.com/neoforged/NeoForge/blob/26.1.x/src/main/java/net/neoforged/neoforge/capabilities/CapabilityHooks.java
[energyhandler]: transactions.md#energy-handler
[invalidation]: #block-capability-invalidation
[itemaccess]: transactions.md#item-access
[resourcehandler]: transactions.md#resource-handlers

## inventories/container

---
sidebar_position: 0
---
# Containers

A popular use case of [block entities][blockentity] is to store items of some kind. Some of the most essential [blocks][block] in Minecraft, such as the furnace or the chest, use block entities for this purpose. To store items on something, Minecraft uses `Container`s.

The `Container` interface defines methods such as `#getItem`, `#setItem` and `#removeItem` that can be used to query and update the container. Since it is an interface, it does not actually contain a backing list or other data structure, that is up to the implementing system.

Due to this, `Container`s can not only be implemented on block entities, but any other class as well. Notable examples include entity inventories, as well as common modded [items][item] such as backpacks.

:::warning
NeoForge provides the `ItemStacksResourceHandler` class as a replacement for `Container`s in many places. It should be used wherever possible in favor of `Container`, as it allows for cleaner interaction with other `Container`s/`ItemStacksResourceHandler`s.

The main reason this article exists is for reference in vanilla code, or if you are developing mods on multiple loaders. Always use `ItemStacksResourceHandler` in your own code if possible! Docs on that are a work in progress.
:::

## Basic Container Implementation

Containers can be implemented in any way you like, so long as you satisfy the dictated methods (as with any other interface in Java). However, it is common to use a `NonNullList<ItemStack>` with a fixed length as a backing structure. Single-slot containers may also simply use an `ItemStack` field instead.

For example, a basic implementation of `Container` with a size of 27 slots (one chest) could look like this:

```java
public class MyContainer implements Container {
    private final NonNullList<ItemStack> items = NonNullList.withSize(
            // The size of the list, i.e. the amount of slots in our container.
            27,
            // The default value to be used in place of where you'd use null in normal lists.
            ItemStack.EMPTY
    );

    // The amount of slots in our container.
    @Override
    public int getContainerSize() {
        return 27;
    }

    // Whether the container is considered empty.
    @Override
    public boolean isEmpty() {
        return this.items.stream().allMatch(ItemStack::isEmpty);
    }

    // Return the item stack in the specified slot.
    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    // Remove the specified amount of items from the given slot, returning the stack that was just removed.
    // We defer to ContainerHelper here, which does this as expected for us.
    // However, we must call #setChanged manually.
    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = ContainerHelper.removeItem(this.items, slot, amount);
        this.setChanged();
        return stack;
    }

    // Remove all items from the specified slot, returning the stack that was just removed.
    // We again defer to ContainerHelper here, and we again have to call #setChanged manually.
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = ContainerHelper.takeItem(this.items, slot);
        this.setChanged();
        return stack;
    }

    // Set the given item stack in the given slot. Limit to the max stack size of the container first.
    @Override
    public void setItem(int slot, ItemStack stack) {
        stack.limitSize(this.getMaxStackSize(stack));
        this.items.set(slot, stack);
        this.setChanged();
    }

    // Call this when changes are done to the container, i.e. when item stacks are added, modified, or removed.
    // For example, you could call BlockEntity#setChanged here.
    @Override
    public void setChanged() {

    }

    // Whether the container is considered "still valid" for the given player. For example, chests and
    // similar blocks check if the player is still within a given distance of the block here.
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // Clear the internal storage, setting all slots to empty again.
    @Override
    public void clearContent() {
        items.clear();
        this.setChanged();
    }
}
```

### `SimpleContainer`

The `SimpleContainer` class is a basic implementation of a container with some sprinkles on top. It can be used if you need a container implementation that doesn't have any special requirements.

### `BaseContainerBlockEntity`

The `BaseContainerBlockEntity` class is the base class of many important block entities in Minecraft, such as chests and chest-like blocks, the various furnace types, hoppers, dispensers, droppers, brewing stands and a few others.

Aside from `Container`, it also implements the `MenuProvider` and `Nameable` interfaces:

- `Nameable` defines a few methods related to setting (custom) names and, aside from many block entities, is implemented by classes such as `Entity`. This uses the [`Component` system][component].
- `MenuProvider`, on the other hand, defines the `#createMenu` method, which allows an [`AbstractContainerMenu`][menu] to be constructed from the container. This means that using this class is not desirable if you want a container without an associated GUI, for example in jukeboxes.

`BaseContainerBlockEntity` bundles all calls we would normally make to our `NonNullList<ItemStack>` through two methods `#getItems` and `#setItems`, drastically reducing the amount of boilerplate we need to write. An example implementation of a `BaseContainerBlockEntity` could look like this:

```java
public class MyBlockEntity extends BaseContainerBlockEntity {
    // The container size. This can of course be any value you want.
    public static final int SIZE = 9;
    // Our item stack list. This is not final due to #setItems existing.
    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    // The constructor, like before.
    public MyBlockEntity(BlockPos pos, BlockState blockState) {
        super(MY_BLOCK_ENTITY.get(), pos, blockState);
    }

    // The container size, like before.
    @Override
    public int getContainerSize() {
        return SIZE;
    }

    // The getter for our item stack list.
    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    // The setter for our item stack list.
    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    // The display name of the menu. Don't forget to add a translation!
    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.examplemod.myblockentity");
    }

    // The menu to create from this container. See below for what to return here.
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return null;
    }
}
```

Keep in mind that this class is a `BlockEntity` and a `Container` at the same time. This means that you can use the class as a supertype for your block entity to get a functioning block entity with a pre-implemented container.

:::note
`BlockEntity`s that implement `Container` handle dropping their contents by default. If you choose not to implement `Container`, then you will need to handle the [removal logic][beremove].
:::

### `WorldlyContainer`

`WorldlyContainer` is a sub-interface of `Container` that allows accessing slots of the given `Container` by `Direction`. It is mainly intended for block entities that only expose parts of their container to a particular side. For example, this could be used by a machine that outputs to one side and takes inputs from all other sides, or vice-versa. A simple implementation of the interface could look like this:

```java
// See BaseContainerBlockEntity methods above. You can of course extend BlockEntity directly
// and implement Container yourself if needed.
public class MyBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    // other stuff here
    
    // Assume that slot 0 is our output and slots 1-8 are our inputs.
    // Further assume that we output to the top and take inputs from all other sides.
    private static final int[] OUTPUTS = new int[]{0};
    private static final int[] INPUTS = new int[]{1, 2, 3, 4, 5, 6, 7, 8};

    // Return an array of exposed slot indices based on the passed Direction.
    @Override
    public int[] getSlotsForFace(Direction side) {
        return side == Direction.UP ? OUTPUTS : INPUTS;
    }

    // Whether items can be placed through the given side at the given slot.
    // For our example, we return true only if we're not inputing from above and are in the index range [1, 8].
    @Override
    public boolean canPlaceItemThroughFace(int index, ItemStack itemStack, @Nullable Direction direction) {
        return direction != Direction.UP && index > 0 && index < 9;
    }

    // Whether items can be taken from the given side and the given slot.
    // For our example, we return true only if we're pulling from above and from slot index 0.
    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return direction == Direction.UP && index == 0;
    }
}
```

## Using Containers

Now that we have created containers, let's use them!

Since there is a considerable overlap between `Container`s and `BlockEntity`s, containers are best retrieved by casting the block entity to `Container` if possible:

```java
if (blockEntity instanceof Container container) {
    // do something with the container
}
```

The container can then use the methods we mentioned before, for example:

```java
// Get the first item in the container.
ItemStack stack = container.getItem(0);

// Set the first item in the container to dirt.
container.setItem(0, new ItemStack(Items.DIRT));

// Removes a quantity of (up to) 16 from the third slot.
container.removeItem(2, 16);
```

:::warning
A container may throw an exception if trying to access a slot that is beyond its container size. Alternatively, they may return `ItemStack.EMPTY`, as is the case with (for example) `SimpleContainer`.
:::

### `ContainerUser`s

Living entities that are able to access containers implement `ContainerUser`. Each user defines whether it has a container open and the maximum block distance the entity can interact with the container. A `Container` calls `startOpen` with the `ContainerUser` when the container object is interacted with (e.g., right-clicking a chest), and `stopOpen` once the container object is closed (e.g., leaving the chest menu).

These methods are typically used to keep track of the number of living entities that have the container open through the `ContainerOpenersCounter`, which is used for some entity AI and rendering.

## `Container`s on `ItemStack`s

Until now, we mainly discussed `Container`s on `BlockEntity`s. However, they can also be applied to [`ItemStack`s][itemstack] using the `minecraft:container` [data component][datacomponent]:

```java
// We use SimpleContainer as the superclass here so we don't have to reimplement the item handling logic ourselves.
// Due to implementation details of SimpleContainer, this may lead to race conditions if multiple parties
// can access the container at the same time, so we're just going to assume our mod doesn't allow that.
// You may of course use a different implementation of Container (or implement Container yourself) if needed.
public class MyBackpackContainer extends SimpleContainer {
    // The item stack this container is for. Passed into and set in the constructor.
    private final ItemStack stack;
    
    public MyBackpackContainer(ItemStack stack) {
        // We call super with our desired container size.
        super(27);
        // Setting the stack field.
        this.stack = stack;
        // We load the container contents from the data component (if present), which is represented
        // by the ItemContainerContents class. If absent, we use ItemContainerContents.EMPTY.
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        // Copy the data component contents into our item stack list.
        contents.copyInto(this.getItems());
    }

    // When the contents are changed, we save the data component on the stack.
    @Override
    public void setChanged() {
        super.setChanged();
        this.stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(this.getItems()));
    }
}
```

And voilà, you have created an item-backed container! Call `new MyBackpackContainer(stack)` to create a container for a menu or other use case.

:::warning
Be aware that menus that directly interface with `Container`s must `#copy()` their `ItemStack`s when modifying them, as otherwise the immutability contract on data components is broken. To do this, NeoForge provides the `StackCopySlot` class for you.
:::

## `Container`s on `Entity`s

`Container`s on [`Entity`s][entity] are finicky: whether an entity has a container or not cannot be universally determined. It all depends on what entity you are handling, and as such can require a lot of special-casing.

If you are creating an entity yourself, there is nothing stopping you from implementing `Container` on it directly, though be aware that you will not be able to use superclasses such as `SimpleContainer` (since `Entity` is the superclass).

### `Container`s on `Mob`s

`Mob`s do not implement `Container`, but they implement the `EquipmentUser` interface (among others). This interface defines the methods `#setItemSlot(EquipmentSlot, ItemStack)`, `#getItemBySlot(EquipmentSlot)` and `#setDropChance(EquipmentSlot, float)`. While not related to `Container` code-wise, the functionality is quite similar: we associate slots, in this case equipment slots, with `ItemStack`s.

The most notable difference to `Container` is that there is no list-like order (though `Mob` uses `NonNullList<ItemStack>`s in the background). Access does not work through slot indices, but rather through the seven `EquipmentSlot` enum values: `MAINHAND`, `OFFHAND`, `FEET`, `LEGS`, `CHEST`, `HEAD`, and `BODY` (where `BODY` is used for horse and dog armor).

An example of interaction with the mob's "slots" would look something like this:

```java
// Get the item stack in the HEAD (helmet) slot.
ItemStack helmet = mob.getItemBySlot(EquipmentSlot.HEAD);

// Put bedrock into the mob's FEET (boots) slot.
mob.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.BEDROCK));

// Enable that bedrock to always drop if the mob is killed.
mob.setDropChance(EquipmentSlot.FEET, 1f);
```

### `InventoryCarrier`

`InventoryCarrier` is an interface implemented by some living entities, such as villagers. It declares a method `#getInventory`, which returns a `SimpleContainer`. This interface is used by non-player entities that need an actual inventory instead of just the equipment slots provided by `EquipmentUser`.

### `Container`s on `Player`s (Player Inventory)

The player's inventory is implemented through the `Inventory` class, a class implementing `Container` as well as the `Nameable` interface mentioned earlier. An instance of that `Inventory` is then stored as a field named `inventory` on the `Player`, accessible via `Player#getInventory`. The inventory can be interacted with like any other container.

The inventory contents are stored in two places:

- The `NonNullList<ItemStack> items` list covers the 36 main inventory slots, including the nine hotbar slots (indices 0-8).
- The `EntityEquipment equipment` map stores the `EquipmentSlot` stacks: the armor slots (`FEET`, `LEGS`, `CHEST`, `HEAD`), `OFFHAND`, `BODY`, and `SADDLE`, in that order.  

When iterating over the inventory contents, it is recommended to iterate over `items`, then over `equipment` using `Inventory#EQUIPMENT_SLOT_MAPPING` for the indices.

[beremove]: ../blockentities/index.md#removing-block-entities
[block]: ../blocks/index.md
[blockentity]: ../blockentities/index.md
[component]: ../resources/client/i18n.md#components
[datacomponent]: ../items/datacomponents.md
[entity]: ../entities/index.md
[item]: ../items/index.md
[itemstack]: ../items/index.md#itemstacks
[menu]: menus.md

## inventories/menus

---
sidebar_position: 2
---
# Menus

Menus are one type of backend for Graphical User Interfaces, or GUIs; they handle the logic involved in interacting with some represented data holder. Menus themselves are not data holders. They are views which allow to user to indirectly modify the internal data holder state. As such, a data holder should not be directly coupled to any menu, instead passing in the data references to invoke and modify.

## `MenuType`

Menus are created and removed dynamically and as such are not registry objects. As such, another factory object is registered instead to easily create and refer to the *type* of the menu. For a menu, these are `MenuType`s.

`MenuType`s must be [registered].

### `MenuSupplier`

A `MenuType` is created by passing in a `MenuSupplier` and a `FeatureFlagSet` to its constructor. A `MenuSupplier` represents a function which takes in the id of the container and the inventory of the player viewing the menu, and returns a newly created [`AbstractContainerMenu`][acm].

```java
// For some DeferredRegister<MenuType<?>> REGISTER
public static final Supplier<MenuType<MyMenu>> MY_MENU = REGISTER.register("my_menu", () -> new MenuType<>(MyMenu::new, FeatureFlags.DEFAULT_FLAGS));

// In MyMenu, an AbstractContainerMenu subclass
public MyMenu(int containerId, Inventory playerInv) {
    super(MY_MENU.get(), containerId);
    // ...
}
```

:::note
The container identifier is unique for an individual player. This means that the same container id on two different players will represent two different menus, even if they are viewing the same data holder.
:::

The `MenuSupplier` is usually responsible for creating a menu on the client with dummy data references used to store and interact with the synced information from the server data holder.

### `IContainerFactory`

If additional information is needed on the client (e.g. the position of the data holder in the world), then the subclass `IContainerFactory` can be used instead. In addition to the container id and the player inventory, this also provides a `RegistryFriendlyByteBuf` which can store additional information that was sent from the server. A `MenuType` can be created using an `IContainerFactory` via `IMenuTypeExtension#create`.

```java
// For some DeferredRegister<MenuType<?>> REGISTER
public static final Supplier<MenuType<MyMenuExtra>> MY_MENU_EXTRA = REGISTER.register("my_menu_extra", () -> IMenuTypeExtension.create(MyMenu::new));

// In MyMenuExtra, an AbstractContainerMenu subclass
public MyMenuExtra(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
    super(MY_MENU_EXTRA.get(), containerId);
    // Store extra data from buffer
    // ...
}
```

## `AbstractContainerMenu`

All menus are extended from `AbstractContainerMenu`. A menu takes in two parameters, the [`MenuType`][mt], which represents the type of the menu itself, and the container id, which represents the unique identifier of the menu for the current accessor.

:::note
The menu identifier cycles through 0-99, incrementing whenever a player opens a menu.
:::

Each menu should contain two constructors: one used to initialize the menu on the server and one used to initialize the menu on the client. The constructor used to initialize the menu on the client is the one supplied to the `MenuType`. Any fields that the server menu constructor contains should have some default for the client menu constructor.

```java
// Client menu constructor
public MyMenu(int containerId, Inventory playerInventory) { // optional FriendlyByteBuf parameter if reading data from server
    this(containerId, playerInventory, /* Any default parameters here */);
}

// Server menu constructor
public MyMenu(int containerId, Inventory playerInventory, /* Any additional parameters here. */) {
    // ...
}
```

:::note
If no additional data needs to be displayed in the menu, then only one constructor is necessary.
:::

Each menu implementation must implement two methods: `#stillValid` and [`#quickMoveStack`][qms].

### `#stillValid` and `ContainerLevelAccess`

`#stillValid` determines whether the menu should remain open for a given player. This is typically directed to the static `#stillValid` which takes in a `ContainerLevelAccess`, the player, and the `Block` this menu is attached to. The client menu must always return `true` for this method, which the static `#stillValid` does default to. This implementation checks whether the player is within eight blocks of where the data storage object is located.

A `ContainerLevelAccess` supplies the current level and block position within an enclosed scope. When constructing the menu on the server, a new access can be created by calling `ContainerLevelAccess#create`. The client menu constructor can pass in `ContainerLevelAccess#NULL`, which will do nothing.

```java
// Client menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory) {
    this(containerId, playerInventory, ContainerLevelAccess.NULL);
}

// Server menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
    // ...
}

// Assume this menu is attached to Supplier<Block> MY_BLOCK
@Override
public boolean stillValid(Player player) {
    return AbstractContainerMenu.stillValid(this.access, player, MY_BLOCK.get());
}
```

### Data Synchronization

Some data needs to be present on both the server and the client to display to the player. To do this, the menu implements a basic layer of data synchronization such that whenever the current data does not match the data last synced to the client. For players, this is checked every tick.

Minecraft supports two forms of data synchronization by default: [`ItemStack`s][itemstack] via `Slot`s and integers via `DataSlot`s. `Slot`s and `DataSlot`s are views which hold references to data storages that can be be modified by the player in a screen, assuming the action is valid. Each method of data synchronization will add an argument to the server menu constructor, while the client will create a dummy instance used to write the data sent from the server.

#### `DataSlot`

A `DataSlot` is an abstract class which should implement a getter and setter to reference the data stored in the data storage object. The client menu constructor should always supply a new instance via `DataSlot#standalone`. The data slot can then be added to the menu using `#addDataSlot`.

These, along with slots, should be recreated every time a new menu is initialized.

:::note
Although a `DataSlot` stores an integer, it is effectively limited to a **short** (-32768 to 32767) because of how it sends the value across the network. The 16 high-order bits of the integer are ignored.

NeoForge patches the packet to provide the full integer to the client.
:::

```java
// Assume we have a DataSlot constructed on each initialization of the server menu

// Client menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory) {
    this(
        containerId, playerInventory,
        // Pass in a dummy slot to hold the server-synced values
        DataSlot.standalone()
    );
}

// Server menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory, DataSlot dataSingle) {
    // Add data slots for handled integers
    this.addDataSlot(dataSingle);

    // ...
}
```

#### `ContainerData`

If multiple integers need to be synced to the client, a `ContainerData` can be used to reference the integers instead. This interface functions as an index lookup such that each index represents a different integer. `ContainerData`s can also be constructed in the data object itself if the `ContainerData` is added to the menu through `#addDataSlots`. The method creates a new `DataSlot` for the amount of data specified by the interface. The client menu constructor should always supply a new instance via `SimpleContainerData`.

```java
// Assume we have a ContainerData of size 3

// Client menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory) {
    this(containerId, playerInventory, new SimpleContainerData(3));
}

// Server menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory, ContainerData dataMultiple) {
    // Check if the ContainerData size is some fixed value
    checkContainerDataCount(dataMultiple, 3);

    // Add data slots for handled integers
    this.addDataSlots(dataMultiple);

    // ...
}
```

#### `Slot`

A `Slot` represents a reference to some [`ItemStack`][itemstack] in an inventory. Each `Slot` has at least four parameters: the inventory the stacks are within, the index of the stack this slot is specifically representing, and the x and y position of where the top-left position of the slot will render on the screen relative to `AbstractContainerScreen#leftPos` and `#topPos`. Any additional parameters usually provide context for the slot to handle unique behavior, such as taking in only items that are considered fuel or preventing items from being taken out. 

The server menu constructor should take in the inventory instance or view. The client menu constructor, meanwhile, should always supply an empty instance of an inventory of the same size to write the server data to. The desired slot or one of its subtypes can then be added to the menu using `#addSlot`.

For a [`Container`][container], the client menu will typically pass in a `SimpleContainer` and be added using regular `Slot`s. For the [`ResourceHandler<ItemResource>` capability][cap], the client menu will typically pass in a `ItemStacksResourceHandler` and be added using `ResourceHandlerSlot`s.

In most cases, any slots the menu contains is first added, followed by the player's inventory, and finally concluded with the player's hotbar. To access any individual `Slot` from the menu, the index must be calculated based upon the order of which slots were added.

```java
// Assume we have an inventory from a data object of size 10

// Client menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory) {
    this(containerId, playerInventory, new ItemStacksResourceHandler(10));
}

// Server menu constructor
public MyMenuAccess(int containerId, Inventory playerInventory, StacksResourceHandler<ItemStack, ItemResource> dataInventory) {
    // Check if the data inventory size is some fixed value
    int dataSize = dataInventory.getSlots();
    if (dataSize < 10) {
        throw new IllegalArgumentException("Container size " + dataSize + " is smaller than expected " + 5);
    }

    // Then, add slots for data inventory
    // If you are using a subtype of slot, make sure any data
    // used on the server is also available on the client.

    // Create the inventory by looping through the positions and
    // adding the slots.

    // Two rows
    for (int j = 0; j < 2; j++) {
        // Five columns
        for (int i = 0; i < 5; i++) {
            // Add for each slot in the data inventory
            this.addSlot(new ResourceHandlerSlot(
                // The inventory
                dataInventory,
                // The index modifier to mutate the stored resources
                dataInventory::set,
                // The index of the data inventory this slot represents:
                // rowIndex * columnCount + columnIndex
                j * 5 + i,
                // The x position relative to leftPos
                // Vanilla slots are 18 units by default
                // startX + columnIndex * slotRenderWidth
                44 + i * 18,
                // The y position relative to topPos
                // Vanilla slots are 18 units by default
                // startY + rowIndex * slotRenderHeight
                20 + j * 18
            ))
        }
    }

    // Add slots for player inventory (all 27 + 9 hotbar slots)
    // If you want to customize the 9x3 + 9 grid to something else,
    // loop through like above
    this.addStandardInventorySlots(
        playerInventory,
        // The starting x position relative to leftPos
        8,
        // The starting y position relative to topPos
        84
    );

    // ...
}
```

#### `#quickMoveStack`

`#quickMoveStack` is the second method that must be implemented by any menu. This method is called whenever a stack has been shift-clicked, or quick moved, out of its current slot until the stack has been fully moved out of its previous slot or there is no other place for the stack to go. The method returns a copy of the stack in the slot being quick moved.

Stacks are typically moved between slots using `#moveItemStackTo`, which moves the stack into the first available slot. It takes in the stack to be moved, the first slot index (inclusive) to try and move the stack to, the last slot index (exclusive), and whether to check the slots from first to last (when `false`) or from last to first (when `true`).

Across Minecraft implementations, this method is fairly consistent in its logic:

```java
// Assume we have a data inventory of size 5
// The inventory has 4 inputs (index 1 - 4) which outputs to a result slot (index 0)
// We also have the 27 player inventory slots and the 9 hotbar slots
// As such, the actual slots are indexed like so:
//   - Data Inventory: Result (0), Inputs (1 - 4)
//   - Player Inventory (5 - 31)
//   - Player Hotbar (32 - 40)
@Override
public ItemStack quickMoveStack(Player player, int quickMovedSlotIndex) {
    // The quick moved slot stack
    ItemStack quickMovedStack = ItemStack.EMPTY;
    // The quick moved slot
    Slot quickMovedSlot = this.slots.get(quickMovedSlotIndex);
  
    // If the slot is in the valid range and the slot is not empty
    if (quickMovedSlot != null && quickMovedSlot.hasItem()) {
        // Get the raw stack to move
        ItemStack rawStack = quickMovedSlot.getItem(); 
        // Set the slot stack to a copy of the raw stack
        quickMovedStack = rawStack.copy();

        /*
        The following quick move logic can be simplified to if in data inventory,
        try to move to player inventory/hotbar and vice versa for containers
        that cannot transform data (e.g. chests).
        */

        // If the quick move was performed on the data inventory result slot
        if (quickMovedSlotIndex == 0) {
            // Try to move the result slot into the player inventory/hotbar
            if (!this.moveItemStackTo(rawStack, 5, 41, true)) {
                // If cannot move, no longer quick move
                return ItemStack.EMPTY;
            }

            // Perform logic on result slot quick move
            quickMovedSlot.onQuickCraft(rawStack, quickMovedStack);
        }
        // Else if the quick move was performed on the player inventory or hotbar slot
        else if (quickMovedSlotIndex >= 5 && quickMovedSlotIndex < 41) {
            // Try to move the inventory/hotbar slot into the data inventory input slots
            if (!this.moveItemStackTo(rawStack, 1, 5, false)) {
                // If cannot move and in player inventory slot, try to move to hotbar
                if (quickMovedSlotIndex < 32) {
                    if (!this.moveItemStackTo(rawStack, 32, 41, false)) {
                        // If cannot move, no longer quick move
                        return ItemStack.EMPTY;
                    }
                }
                // Else try to move hotbar into player inventory slot
                else if (!this.moveItemStackTo(rawStack, 5, 32, false)) {
                    // If cannot move, no longer quick move
                    return ItemStack.EMPTY;
                }
            }
        }
        // Else if the quick move was performed on the data inventory input slots, try to move to player inventory/hotbar
        else if (!this.moveItemStackTo(rawStack, 5, 41, false)) {
            // If cannot move, no longer quick move
            return ItemStack.EMPTY;
        }

        if (rawStack.isEmpty()) {
            // If the raw stack has completely moved out of the slot, set the slot to the empty stack
            quickMovedSlot.setByPlayer(ItemStack.EMPTY);
        } else {
            // Otherwise, notify the slot that that the stack count has changed
            quickMovedSlot.setChanged();
        }

        // Execute logic on what to do post move with the remaining stack
        // This can be removed if there are no `Slot` subtypes that override `onTake`
        quickMovedSlot.onTake(player, rawStack);
    }

    return quickMovedStack; // Return the slot stack
}
```

## Opening a Menu

Once a menu type has been registered, the menu itself has been finished, and a [screen] has been attached, a menu can then be opened by the player. Menus can be opened by calling `IPlayerExtension#openMenu` on the logical server. The method takes in the `MenuProvider` of the server side menu and optionally a `Consumer<RegistryFriendlyByteBuf>` if extra data needs to be synced to the client.

:::note
`IPlayerExtension#openMenu` with the `Consumer<RegistryFriendlyByteBuf>` parameter should only be used if a menu type was created using an [`IContainerFactory`][icf].
:::

#### `MenuProvider`

A `MenuProvider` is an interface that contains two methods: `#createMenu`, which creates the server instance of the menu, and `#getDisplayName`, which returns a component containing the title of the menu to pass to the [screen]. The `#createMenu` method contains three parameter: the container id of the menu, the inventory of the player who opened the menu, and the player who opened the menu.

A `MenuProvider` can easily be created using `SimpleMenuProvider`, which takes in a method reference to create the server menu and the title of the menu.

```java
// In some implementation with access to the Player on the logical server (e.g. ServerPlayer instance)
// Assume we have ServerPlayer serverPlayer
serverPlayer.openMenu(new SimpleMenuProvider(
    (containerId, playerInventory, player) -> new MyMenu(containerId, playerInventory, /* server parameters */),
    Component.translatable("menu.title.examplemod.mymenu")
));
```

### Common Implementations

Menus are typically opened on a player interaction of some kind (e.g. when a block or entity is right-clicked).

#### Block Implementation

Blocks typically implement a menu by overriding `BlockBehaviour#useWithoutItem`, returning `InteractionResult#SUCCESS` for the [interaction].

The `MenuProvider` should be implemented by overriding `BlockBehaviour#getMenuProvider`. Vanilla methods use this to view the menu in spectator mode.

```java
// In some Block subclass
@Override
public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
    return new SimpleMenuProvider(/* ... */);
}

@Override
public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult result) {
    if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
        serverPlayer.openMenu(state.getMenuProvider(level, pos));
    }

    return InteractionResult.SUCCESS;
}
```

:::note
This is the simplest way to implement the logic, not the only way. If you want the block to only open the menu under certain conditions, then some data will need to be synced to the client beforehand to return `InteractionResult#PASS` or `#FAIL` if the conditions are not met.
:::

#### Mob Implementation

Mobs typically implement a menu by overriding `Mob#mobInteract`. This is done similarly to the block implementation with the only difference being that the `Mob` itself should implement `MenuProvider` to support spectator mode viewing.

```java
public class MyMob extends Mob implements MenuProvider {
    // ...

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this);
        }

        return InteractionResult.SUCCESS;
    }
}
```

:::note
Once again, this is the simplest way to implement the logic, not the only way.
:::

[registered]: ../concepts/registries.md#methods-for-registering
[acm]: #abstractcontainermenu
[mt]: #menutype
[qms]: #quickmovestack
[cap]: capabilities.md#neoforge-provided-capabilities
[container]: container.md
[screen]: ../rendering/screens.md
[icf]: #icontainerfactory
[side]: ../concepts/sides.md#the-logical-side
[interaction]: ../items/interactions.md#right-clicking-an-item
[itemstack]: ../items/index.md#itemstacks

## inventories/transactions

# Transactions

Transactions are a NeoForged-added system for managing the communication between different inventories transferring their contents. Each transfer is managed through three basic concepts: the `Resource`s being transferred, the `ResourceHandler`s representing the inventories, and the `Transaction` facilitating the communication.

## Resources

`Resource`s represent the backing object that is transacted upon. Each `Resource` is meant to be immutable, containing what kind of object is used, not the number of objects being transferred. For example, the transaction 'five apples for an emerald', contains the `Resource`s 'apple' and 'emerald', not 'five apples' and 'one emerald'.

As such, every `Resource` has the following three properties:

* **Immutability**: Anything stored in the `Resource` object should be non-changing.
* **Count Agnostic**: The `Resource` does not contain any information about how much of an object there is.
* **Equality**: No matter how the `Resource` is constructed, if they represent the same object, they must be equal.

NeoForge provides resources for [items] (via `ItemResource`) and fluids (via `FluidResource`) by representing the object along with its unique [data components][datacomponent].

```java
// Create the resource from its backing object
ItemResource item = ItemResource.of(Items.EMERALD);

ItemStack stack = new ItemStack(Items.APPLE);
stack.set(DataComponents.CUSTOM_NAME, Component.literal("Apple?"));
ItemResource itemWithComponents = ItemResource.of(stack);

FluidResource fluid = FluidResource.of(Fluids.WATER);
```

We can also create our own `Resource` like so:

```java
// Let's assume we are trying to represent the following object:
public class ExampleObject {
    public static final ExampleObject EMPTY = new ExampleObject(-1, 0, Map.of());

    public static final Codec<ExampleObject> CODEC = RecordCodecBuilder.of(instance ->
        instance.group(
            ExtraCodecs.NON_NEGATIVE_INT.fieldOf("id").forGetter(ExampleObject::id),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("count", 1).forGetter(ExampleObject::count),
            Codec.unboundedMap(Codec.STRING, Codec.BOOL).optionalFieldOf("flags", Map::of).forGetter(ExampleObject::flags)
        ).apply(instance, ExampleObject::new)
    );

    private final int id;
    private final Map<String, Boolean> flags;
    private int count;

    public ExampleObject(int id, int count, Map<String, Boolean> flags) {
        // ...
    }

    public int id() {
        return this.id;
    }

    public int count() {
        return this.count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Map<String, Boolean> flags() {
        return this.flags;
    }
}

// Create our resource.
public final class ExampleResource implements Resource {

    private final ExampleObject object;

    public ExampleResource(ExampleObject object) {
        // Enforce immutability and ignore count.
        this.object = new ExampleObject(
            object.id(), 1, ImmutableMap.copyOf(object.flags())
        );
    }

    public int id() {
        return this.object.id();
    }

    public Map<String, Boolean> flags() {
        return this.object.flags();
    }

    // Defines when the backing object is considered empty.
    // This is the only method that `Resource` defines.
    @Override
    public boolean isEmpty() {
        return this.object.id() == -1;
    }

    // Equality for classes is defined by implementing `hashCode`
    // and `equals`. Records already do this for you.
    @Override
    public int hashCode() {
        // Since our backing object is not unique by itself, we
        // extract the components that make it unique and construct
        // the hash.
        return Objects.hash(this.object.id(), this.object.flags());
    }

    @Override
    public boolean equals(Object obj) {
        // Check identity equality.
        if (this == obj) return true;
        // Check if same class.
        if (obj == null || this.getClass() != obj.getClass()) return false;
        // Check the individual components of the resource.
        ExampleResource other = (ExampleResource) obj;
        return this.object.id() == other.object.id()
            && this.object.flags().equals(other.object.flags());
    }

    // Just an ease of convenience to more easily understand what
    // the resource is representing.
    @Override
    public String toString() {
        return Integer.toString(this.object.id()) + "[" 
            + this.object.flags().size() + "]";
    }
}
```

:::note
While `Resource`s can be used for primitives, they are not strictly necessary (e.g., energy does not have a `Resource` as it is backed by a `long`). However, it does require reimplementing some of the resource behavior yourself, as the [handler system described below][handler] requires the use of a `Resource`.
:::

## Resource Handlers

`ResourceHandler<T>`s represent the backing inventories within a transaction, where `T` is the type of the `Resource` backing the object. Each handler maps to its associated contents using an index (e.g., index `0` maps to the first slot, index `1` maps to the second, etc). For every index, you can check whether a `Resource` can be contained at the location (`isValid`) or what `Resource` is already stored there (`getResource`). You can also check how many `Resource`s can be stored at the location (`getCapacityAsLong` / `getCapacityAsInt`) along with how many of a `Resource` is stored there (`getAmountAsLong` / `getAmountAsInt`). The number of indices accessible to the handler represents its `size`.

To modify the contents of the backing inventory, `ResourceHandler` provides two methods: `insert` to put a `Resource` in, and `extract` to take a `Resource` out. `insert` and `extract` take in three arguments: the `Resource` being operated upon, the `int` amount to put in / take out, and a `TransactionContext` representing what [transaction] that is performing the operation, returning the amount put in / taken out. Both of these methods will find the first indices available to put in / take out the contents to / from. If the handler should only transact on one specific index, then both `insert` and `extract` provide an overload that takes in the `int` index to put in / take out `Resource`s to / from.

```java
// For some ResourceHandler<ItemResource> handler

// Get the resource stored in the handler.
ItemResource item = handler.getResource(0);
int count = handler.getAmountAsInt(0);

// Get information about the handler itself.
int handlerSize = handler.size();
int indexCapacity = handler.getCapacityAsInt(0);
boolean canAcceptApples = handler.isValid(0, ItemResource.of(Items.APPLE));
```

There are many different types of `ResourceHandler`s depending on what the backing inventory is. Some handlers wrap around existing vanilla inventories (e.g., `VanillaContainerWrapper` for [`Container`s][container], `PlayerInventoryWrapper` for [player `Inventory`s][playerinv], `LivingEntityEquipmentWrapper` for a [living entity's][livingentity] equipment slots).

```java
// Wrapping around an existing container.
Container container = new SimpleContainer(5);
ResourceHandler<ItemResource> containerWrapper = VanillaContainerWrapper.of(container);

// Wrapping around a `Player` player inventory.
ResourceHandler<ItemResource> playerInv = PlayerInventoryWrapper.of(player);

// Wrapping around a specific equipment slot for some LivingEntity entity.
ResourceHandler<ItemResource> head = LivingEntityEquipmentWrapper.of(entity, EquipmentSlot.HEAD);
```

Other handlers are themselves inventories, providing a convenience for those wanting to make use of the system without much implementing (e.g., `ItemStacksResourceHandler` for a list of [`ItemStack`s][itemstack], `FluidStacksResourceHandler` for a list of `FluidStack`s).

```java
// Creating an `ItemStack` storage.
ItemStacksResourceHandler itemStorage = new ItemStacksResourceHandler(5);

// Creating a `FluidStack` storage.
FluidStacksResourceHandler fluidStorage = new FluidStacksResourceHandler(
    // The size of the handler
    5,
    // The maximum capacity of every index
    1000
);
```

:::note
If you plan to use one of the `StacksResourceHandler`s as an inventory, it is highly recommended to override `onContentsChanged` to handle any disk writing or network syncing.

```java
// Example for block entities
public class ExampleBlockEntity extends BlockEntity {

    private final ItemStacksResourceHandler storage = new ItemStacksResourceHandler(5) {
        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            // Schedule the block entity for saving
            BlockEntity.this.setChanged();
        }
    };

    // ...
}
```

:::

We can also create our own `ResourceHandler` like so:

```java
public class ExampleResourceHandler implements ResourceHandler<ExampleResource> {

    private ExampleObject object;

    public ExampleResourceHandler(ExampleObject object) {
        this.object = object;
    }
    
    @Override
    public int size() {
        // The size of the handler.
        return 1;
    }

    @Override
    public ExampleResource getResource(int index) {
        // Gets the resource at the desired index.

        // Check the bounds.
        Objects.checkIndex(index, this.size());
        // Then get the resource.
        return new ExampleResource(this.object);
    }

    @Override
    public long getAmountAsLong(int index) {
        // Gets the amount from the content.
        Objects.checkIndex(index, this.size());
        return this.object.count();
    }

    @Override
    public long getCapacityAsLong(int index, ExampleResource resource) {
        // The capacity at a given index for the stored resource.
        Objects.checkIndex(index, this.size());
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isValid(int index, ExampleResource resource) {
        // Whether the resource can be set at the index, regardless of its
        // current contents.
        Objects.checkIndex(index, this.size());
        // Make sure the resource isn't empty.
        TransferPreconditions.checkNonEmpty(resource);
        return true;
    }

    @Override
    public int insert(int index, ExampleResource resource, int amount, TransactionContext transaction) {
        // Inserts the resource into the given index, returning the amount put in.

        // Validate arguments.
        Objects.checkIndex(index, size());
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);

        // Check whether the resource can be inserted from this location.
        ExampleObject current = this.object;
        if (current.count() == 0 || (current.id() == resource.id() && current.flags().equals(resource.flags()) && this.isValid(index, resource))) {
            // Compute the amount to insert.
            int insertedAmount = Math.min(amount, this.getCapacityAsInt(index, resource) - current.count());

            if (insertedAmount > 0) {
                // Update the content.
                if (current.count() == 0) {
                    this.object = new ExampleObject(
                        resource.id(), insertedAmount, new HashMap<>(resource.flags())
                    );
                } else {
                    this.object.setCount(current.count() + insertedAmount);
                }

                // Return the amount inserted.
                return insertedAmount;
            }
        }

        // If not matching, insert nothing.
        return 0;
    }

    @Override
    public int extract(int index, ExampleResource resource, int amount, TransactionContext transaction) {
        // Extracts the contents from the given index, returning the amount taken out.

        // Validate arguments.
        Objects.checkIndex(index, size());
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);

        // Check whether the resource can be extracted from this location.
        ExampleObject current = this.object;
        if (current.id() == resource.id() && current.flags().equals(resource.flags())) {
            // Compute the amount to extract.
            int extracted = Math.min(current.count(), amount);

            if (extracted > 0) {
                // Update the content.
                this.object.setCount(current.count() - extracted);

                // Return the amount extracted.
                return extracted;
            }
        }

        // If not matching, extract nothing.
        return 0;
    }
}
```

Or for a `StacksResourceHandler`:

```java
public class ExampleStacksResourceHandler extends StacksResourceHandler<ExampleObject, ExampleResource> {

    public ExampleStacksResourceHandler(int size) {
        super(size, ExampleObject.EMPTY, ExampleObject.CODEC);
    }

    public ExampleStacksResourceHandler(NonNullList<ExampleObject> objects) {
        super(objects, ExampleObject.EMPTY, ExampleObject.CODEC);
    }

    @Override
    public ExampleResource getResourceFrom(ExampleObject object) {
        // Constructs the resource from the content.
        return new ExampleResource(object);
    }

    @Override
    public int getAmountFrom(ExampleObject object) {
        // Gets the amount from the content.
        return object.count();
    }

    @Override
    protected ExampleObject getStackFrom(ExampleResource resource, int amount) {
        // Create the content from its resource.
        return new ExampleObject(resource.id(), amount, new HashMap<>(resource.flags()));
    }

    @Override
    protected int getCapacity(int index, ExampleResource resource) {
        // The capacity at a given index for the stored resource.
        return Integer.MAX_VALUE;
    }

    @Override
    protected ExampleObject copyOf(ExampleObject object) {
        // Constructs a copy of the content.
        return new ExampleObject(object.id(), object.count(), new HashMap<>(object.flags()));
    }

    @Override
    public boolean matches(ExampleObject object, ExampleResource resource) {
        // Check if an object matches the stored resource.
        return object.id() == resource.id() && object.flags().equals(resource.flags());
    }
}
```

:::tip
NeoForge also provides a `ResourceStacksResourceHandler`, using `ResourceStack`s as the stored contents, for `Resource` implementations that are themselves the actual objects within an inventory.
:::

### Energy Handler

`EnergyHandler` is a trimmed down version of `ResourceHandler`, only containing one index storing a `long`. As such, it only checks how many units can be stored (`getCapacityAsLong` / `getCapacityAsInt`) along with how many units already stored (`getAmountAsLong` / `getAmountAsInt`). Additionally, `insert` and `extract` no longer take in an index since there's only one, and also no longer require a `Resource`, as the backing object is a primitive.

Like `ResourceHandler`, there are different types of `EnergyHandler`s depending on your use case. The most common one is `SimpleEnergyHandler`, which provides a basic implementation along with a limit for insert / extract.

```java
// Create an energy handler.
EnergyHandler energy = new SimpleEnergyHandler(1000);
```

### Item Access

`ItemAccess` is also a trimmed down version of `ResourceHandler`, providing access to a single item in a specific storage location. This is typically used within [item capabilities][capabilities] to modify the item the capability is attached to. As such, it only provides the resource (`getResource`) and amount of the item currently present (`getAmount`). Additionally, `insert` and `extract` no longer take in an index since there's only one. However, as items can also store data, the `ItemAccess` provides a way to access the stored data in through [capabilities] via `getCapability`, assuming it is an `ItemCapability` with an `ItemAccess` context.

Like `ResourceHandler`, there are different types of `ItemAccess`es depending on usecase. The two most common are `PlayerItemAccess`, which wraps around a specific slot in the player inventory; and `HandlerItemAccess`, which wraps around a specific index in a `ResourceHandler`.

```java
// Create an item access for some location.
// Assume we have some `Player` player.
ItemAccess access = ItemAccess.forPlayerInteraction(player, InteractionHand.MAIN_HAND);

// Get the data about the referenced item
ItemResource item = access.getResource();
int count = access.getAmount();

// Gets the item capability on the stack.
// For example, if the item is a fluid container:
ResourceHandler<FluidResource> fluidContainer = access.getCapability(Capabilities.Fluid.ITEM);
```

## Transferring Between Handlers

`Transaction`s facilitate the transfer of `Resource`s between `ResourceHandler`s. Here, resources are `insert`ed and `extract`ed from their `ResourceHandler`s. A transfer is considered valid or complete after insertion and extraction once `Transaction#commit` has been called.

`Transaction` is `AutoCloseable`, meaning the standard way to initiate a transaction is through a try-with-resources block using `Transaction#openRoot`:

```java
// Let's assume we have two `ResourceHandler<ItemResource>`s apples, emeralds.

// Open the transaction.
try (Transaction tx = Transaction.openRoot()) {
    // Insert and extract from resource handlers.
    ItemResource appleResource = ItemResource.of(Items.APPLE);
    ItemResource emeraldResource = ItemResource.of(Items.EMERALD);

    int numOfApples = apples.extract(appleResource, 5, tx);
    int numOfEmeralds = emeralds.extract(emeraldResource, 1, tx);

    // Perform any validation necessary.
    if (numOfApples == 5 && numOfEmeralds == 1) {
        numOfEmeralds = apples.insert(emeraldResource, numOfEmeralds, tx);
        numOfApples = emeralds.insert(appleResource, numOfApples, tx);

        if (numOfApples == 5 && numOfEmeralds == 1) {
            // Mark the transaction as complete.
            tx.commit();
        }
    }
}
```

:::tip

`ResourceHandlerUtil` provides a number of useful methods for checking the current state of a `ResourceHandler` or transacting between handlers in general. For example, the emerald to apples trade above could've been simplified like so:

```java
// Let's assume we have two `ResourceHandler<ItemResource>`s apples, emeralds.

// Open the transaction.
try (Transaction tx = Transaction.openRoot()) {
    // Insert and extract from resource handlers.
    ItemResource appleResource = ItemResource.of(Items.APPLE);
    ItemResource emeraldResource = ItemResource.of(Items.EMERALD);

    int applesMoved = ResourceHandlerUtil.moveStacking(
        // Moving from apples -> emeralds.
        apples, emeralds,
        // Checks what resource(s) to move.
        appleResource::equals,
        // The number of the resource to move.
        5,
        // The transaction context.
        tx
    );
    int emeraldsMoved = ResourceHandlerUtil.moveStacking(
        emeralds, apples, emeraldResource::equals, 1, tx
    );;

    // Perform any validation necessary.
    if (applesMoved == 5 && emeraldsMoved == 1) {
        // Mark the transaction as complete.
        tx.commit();
    }
}
```

:::

`Transaction`s can also have `Transaction`s within themselves via `Transation#open` if multiple are occurring at the same time.

```java
// Open the transaction.
try (Transaction tx = Transaction.openRoot()) {
    // Transaction A
    try (Transaction atx = Transaction.open(tx)) {
        // Insert and extract from resource handlers.

        // ...

        // Mark as complete.
        atx.commit();
    }

    // Transaction B
    try (Transaction btx = Transaction.open(tx)) {
        // Insert and extract from resource handlers.

        // ...

        // Maybe this one was invalid, so don't mark as complete.
    }

    // Mark the root transaction as successful such that the successful
    // inner transactions are completed.
    tx.commit();
}
```

### Taking Snapshots

On its own, `Transaction#commit` does nothing. As such, the insertions and extractions performed are permanent regardless of whether the transfer was successful or not. What we want is that for any `Transaction`, the transfer only happens if it is `commit`ted. Otherwise, the transfer should be reverted.

This is where the `SnapshotJournal<T>` comes in. As the name implies, it can take a `T` 'snapshot' of the current handler state right before modifying its contents. Then, it can either release the snapshot if the transaction was successful, or it can revert the handler back to its previous state. Each `SnapshotJournal` must implement at least two methods: `createSnapshot` to actually create the saved state, and `revertToSnapshot` to revert the handler back to the specified state. If any backing objects need to be notified or updated due to the changes in the handler, then the journal can also override `onRootCommit` to handle these changes.

All NeoForge `ResourceHandler` implementations use the `SnapshotJournal` in some fashion, either directly on the handler itself or as a field within. It's only when making new `ResourceHandler`s that the `SnapshotJournal` needs to be implemented.

```java
// We can use the stored object as the snapshot value since we only ever
// need to keep track of one index.
public class ExampleResourceHandler extends SnapshotJournal<ExampleObject> implements ResourceHandler<ExampleResource> {

    private ExampleObject object;

    public ExampleResourceHandler(ExampleObject object) {
        // ...
    }
    
    // ...

    @Override
    protected ExampleObject createSnapshot() {
        // Create a snapshot of the object.
        // This should be immutable.
        ExampleObject original = this.object;
        this.object = new ExampleObject(
            original.id(), original.count(), ImmutableMap.copyOf(original.flags())
        );
        return original;
    }

    @Override
    protected void revertToSnapshot(ExampleObject snapshot) {
        // Reverts the state of the handler to the snapshot.
        this.object = snapshot;
    }

    // We need to update the insert and extract methods to make snapshots before
    // every modification.

    @Override
    public int insert(int index, ExampleResource resource, int amount, TransactionContext transaction) {
        // Inserts the resource into the given index, returning the amount put in.

        // Validate arguments.
        Objects.checkIndex(index, size());
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);

        // Check whether the resource can be inserted from this location.
        ExampleObject current = this.object;
        if (current.count() == 0 || (current.id() == resource.id() && current.flags().equals(resource.flags()) && this.isValid(index, resource))) {
            // Compute the amount to insert.
            int insertedAmount = Math.min(amount, this.getCapacityAsInt(index, resource) - current.count());

            if (insertedAmount > 0) {
                // Snapshot the handler before modifying the contents.
                this.updateSnapshots(transaction);

                // Update the content.
                if (current.count() == 0) {
                    this.object = new ExampleObject(
                        resource.id(), insertedAmount, new HashMap<>(resource.flags())
                    );
                } else {
                    this.object.setCount(current.count() + insertedAmount);
                }

                // Return the amount inserted.
                return insertedAmount;
            }
        }

        // If not matching, insert nothing.
        return 0;
    }

    @Override
    public int extract(int index, ExampleResource resource, int amount, TransactionContext transaction) {
        // Extracts the contents from the given index, returning the amount taken out.

        // Validate arguments.
        Objects.checkIndex(index, size());
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);

        // Check whether the resource can be extracted from this location.
        ExampleObject current = this.object;
        if (current.id() == resource.id() && current.flags().equals(resource.flags())) {
            // Compute the amount to extract.
            int extracted = Math.min(current.count(), amount);

            if (extracted > 0) {
                // Snapshot the handler before modifying the contents.
                this.updateSnapshots(transaction);

                // Update the content.
                this.object.setCount(current.count() - extracted);

                // Return the amount extracted.
                return extracted;
            }
        }

        // If not matching, extract nothing.
        return 0;
    }
}
```

With that, our transactions will now properly handle the state of the inventories as well:

```java
// Let's assume we have two `ResourceHandler<ExampleResource>`s exampleA, exampleB.

// Open the transaction.
try (Transaction tx = Transaction.openRoot()) {
    // Insert and extract from resource handlers
    ExampleResource resource = new ExampleResource(new ExampleObject(0, 1, Map.of()));

    // Try to extract and insert the desired resource
    if (exampleA.extract(resource, 1, tx) == 1 && exampleB.insert(resource, 1, tx) == 1) {
        // If successful, commit the transaction to make the change permanent.
        tx.commit();
    }

    // Otherwise, the transaction is aborted and the two handlers will revert their
    // contents to before the transaction occurred.
}
```

[capabilities]: capabilities.md
[container]: container.md
[datacomponent]: ../items/datacomponents.md
[handler]: #resource-handlers
[items]: ../items/index.md
[itemstack]: ../items/index.md#itemstacks
[livingentity]: ../entities/livingentity.md
[playerinv]: container.md#containers-on-players-player-inventory
[transaction]: #transferring-between-handlers

## items/armor

---
sidebar_position: 5
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Armor

Armors are [items][item] whose primary use is to protect a [`LivingEntity`][livingentity] from damage using a variety of resistances and effects. Many mods add new armor sets (for example copper armor).

## Custom Armor Sets

An armor set for a humanoid entity typically consists of four items: a helmet for the head, a chestplate for the chest, leggings for the legs, and boots for the feet. There is also armor for wolves, horses, and llamas that are applied to a 'body' armor slot specifically for animals. All of these items are generally implemented through seven [data components][datacomponents]: 

- `DataComponents#MAX_DAMAGE` and `#DAMAGE` for durability
- `#MAX_STACK_SIZE` to set the stack size to `1`
- `#REPAIRABLE` for repairing an armor piece in an anvil
- `#ENCHANTABLE` for the maximum [enchanting][enchantment] value
- `#ATTRIBUTE_MODIFIERS` for armor, armor toughness, and knockback resistance
- `#EQUIPPABLE` for how the entity can equip the item.

Commonly, each armor is setup using `Item.Properties#humanoidArmor` for humanoid entities, `wolfArmor` for wolves, `horseArmor` for horses, and `nautilusArmor` for nautili. They all use `ArmorMaterial` combined with `ArmorType` for humanoids to set up the components. Reference values can be found within `ArmorMaterials`. This example uses a copper armor material, which you can adjust the values of as needed.

```java
// The resource key of the equipment asset used to link
// the `EquipmentClientInfo` JSON discussed below.
// Points to assets/examplemod/equipment/copper.json
public static final ResourceKey<EquipmentAsset> COPPER_ASSET = ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.fromNamespaceAndPath("examplemod", "copper"));

public static final ArmorMaterial COPPER_ARMOR_MATERIAL = new ArmorMaterial(
    // The durability multiplier of the armor material.
    // ArmorType have different unit durabilities that the multiplier is applied to:
    // - HELMET: 11
    // - CHESTPLATE: 16
    // - LEGGINGS: 15
    // - BOOTS: 13
    // - BODY: 16
    15,
    // Determines the defense value (or the number of half-armors on the bar).
    // Based on ArmorType.
    Util.make(new EnumMap<>(ArmorType.class), map -> {
        map.put(ArmorItem.Type.BOOTS, 2);
        map.put(ArmorItem.Type.LEGGINGS, 4);
        map.put(ArmorItem.Type.CHESTPLATE, 6);
        map.put(ArmorItem.Type.HELMET, 2);
        map.put(ArmorItem.Type.BODY, 4);
    }),
    // Determines the enchantability of the armor. This represents how good the enchantments on this armor will be.
    // Gold uses 25; we put copper slightly below that.
    20,
    // Determines the sound played when equipping this armor.
    // This is wrapped with a Holder.
    SoundEvents.ARMOR_EQUIP_GENERIC,
     // Returns the toughness value of the armor. The toughness value is an additional value included in
    // damage calculation, for more information, refer to the Minecraft Wiki's article on armor mechanics:
    // https://minecraft.wiki/w/Armor#Armor_toughness
    // Only diamond and netherite have values greater than 0 here, so we just return 0.
    0,
    // Returns the knockback resistance value of the armor. While wearing this armor, the player is
    // immune to knockback to some degree. If the player has a total knockback resistance value of 1 or greater
    // from all armor pieces combined, they will not take any knockback at all.
    // Only netherite has values greater than 0 here, so we just return 0.
    0,
    // The tag that determines what items can repair this armor.
    Tags.Items.INGOTS_COPPER,
    // The resource key of the EquipmentClientInfo JSON discussed below.
    COPPER_ASSET
);
```

Now that we have our `ArmorMaterial`, we can use it for [registering] armor:

```java
// ITEMS is a DeferredRegister.Items
public static final DeferredItem<Item> COPPER_HELMET = ITEMS.registerItem(
    "copper_helmet",
    props -> new Item(
        props.humanoidArmor(
            // The material to use.
            COPPER_ARMOR_MATERIAL,
            // The type of armor to create.
            ArmorType.HELMET
        )
    )
);

public static final DeferredItem<Item> COPPER_CHESTPLATE =
    ITEMS.registerItem("copper_chestplate", props -> new Item(props.humanoidArmor(...)));
public static final DeferredItem<Item> COPPER_LEGGINGS =
    ITEMS.registerItem("copper_chestplate", props -> new Item(props.humanoidArmor(...)));
public static final DeferredItem<Item> COPPER_BOOTS =
    ITEMS.registerItem("copper_chestplate", props -> new Item(props.humanoidArmor(...)));

public static final DeferredItem<Item> COPPER_WOLF_ARMOR = ITEMS.registerItem(
    "copper_wolf_armor",
    props -> new Item(
        // The material to use.
        props.wolfArmor(COPPER_ARMOR_MATERIAL)
    )
);

public static final DeferredItem<Item> COPPER_HORSE_ARMOR =
    ITEMS.registerItem("copper_horse_armor", props -> new Item(props.horseArmor(...)));

public static final DeferredItem<Item> COPPER_NAUTILUS_ARMOR =
    ITEMS.registerItem("copper_nautilus_armor", props -> new Item(props.nautilusArmor(...)));
```

If you want to create armor or an armor-like item from scratch, it can be implemented using a combination of the following parts:

- Adding a `Equippable` with your own requirements by setting `DataComponents#EQUIPPABLE` via `Item.Properties#component`.
- Adding attributes to the item (e.g. armor, toughness, knockback) via `Item.Properties#attributes`.
- Adding item durability via `Item.Properties#durability`.
- Allowing the item to be repaired via `Item.Properties#repariable`.
- Allowing the item to be enchanted via `Item.Properties#enchantable`.
- Adding your armor to some of the `minecraft:enchantable/*` `ItemTags` so that your item can have certain enchantments applied to it.

### `Equippable`

`Equippable` is a data component that contains how an entity can equip this item and what handles the rendering in game. This allows any item, regardless of whether it is considered 'armor', to be equipped if this component is available (e.g., saddles, carpets on llamas). Each item with this component can only be equipped to a single `EquipmentSlot`.

An `Equippable` can be created either by directly calling the record constructor or via `Equippable#builder`, which sets the defaults for each field, followed by `build` once finished:

```java
// The resource key of the equipment asset used to link
// the `EquipmentClientInfo` JSON discussed below.
// Points to assets/examplemod/equipment/equippable.json
public static final ResourceKey<EquipmentAsset> EXAMPLE_EQUIPABBLE = ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.fromNamespaceAndPath("examplemod", "equippable"));

// Assume there is some DeferredRegister.Items ITEMS
public static final DeferredItem<Item> EQUIPPABLE = ITEMS.registerSimpleItem(
    "equippable",
    props -> props.component(
        DataComponents.EQUIPPABLE,
        // Sets the slot that this item can be equipped to.
        Equippable.builder(EquipmentSlot.HELMET)
            // Determines the sound played when equipping this item.
            // This is wrapped with a Holder.
            // Defaults to SoundEvents#ARMOR_EQUIP_GENERIC.
            .setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC)
            // The resource key of the EquipmentClientInfo JSON discussed below.
            // When not set, does not render the equipment.
            .setAsset(ResourceKey.create(EXAMPLE_EQUIPABBLE))
            // The relative location of the texture to overlay on the player screen when wearing (e.g., pumpkin blur).
            // Points to assets/examplemod/textures/equippable.png
            // When not set, does not render an overlay.
            .setCameraOverlay(Identifier.withDefaultNamespace("examplemod", "equippable"))
            // A HolderSet of entity types (direct or tag) that can equip this item.
            // When not set, any entity can equip this item.
            .setAllowedEntities(EntityType.ZOMBIE)
            // Whether the item can be equipped when dispensed from a dispenser.
            // Defaults to true.
            .setDispensable(true),
            // Whether the item can be swapped off the player during a quick equip.
            // Defaults to true.
            .setSwappable(false),
            // Whether the item should be damaged when attacked (for equipment typically).
            // Must also be a damageable item.
            // Defaults to true.
            .setDamageOnHurt(false)
            // Whether the item can be equipped onto another entity on interaction (e.g., right click).
            // Defaults to false.
            .setEquipOnInteract(true)
            // When true, an item with the SHEAR_REMOVE_ARMOR item ability can remove the equipped item.
            // Defaults to false.
            .setCanBeSheared(true)
            // The sound to play when shearing this equipped item.
            // This is wrapped with a holder.
            // Defaults to SoundEvents#SHEARS_SNIP.
            .setShearingSound(SoundEvents.SADDLE_UNEQUIP)
            .build()
    )
);
```

## Equipment Assets

Now we have some armor in game, but if we try to wear it, nothing will render since we never specified how to render the equipment. To do so, we need to create an `EquipmentClientInfo` JSON at the location specified by `Equippable#assetId`, relative to the `equipment` folder of the [resource pack][respack] (`assets` folder). The `EquipmentClientInfo` specifies the associated textures to use for each layer to render.

An `EquipmentClientInfo` is functionally a map of `EquipmentClientInfo.LayerType`s to a list of `EquipmentClientInfo.Layer`s to apply.

The `LayerType` can be thought of as a group of textures to render for some instance. For example, `LayerType#HUMANOID` is used by the `HumanoidArmorLayer` to render the head, chest, and feet on humanoid entities; `LayerType#WOLF_BODY` is used by `WolfArmorLayer` to render the body armor. These can be combined into one equipment info JSON if they are for the same type of equippable, like copper armor.

The `LayerType` maps to some list of `Layer`s to apply and render the textures in the order provided. A `Layer` effectively represents a single texture to render. The first parameter represents the location of the texture, relative to `textures/entity/equipment`.

The second parameter is an optional that indicates whether the [texture can be tinted][tinting] as an `EquipmentClientInfo.Dyeable`. The `Dyeable` object holds an integer that, when present, indicates the default RGB color to tint the texture with. If this optional is not present, then pure white is used.

:::warning
For a tint other than the undyed color to be applied to the item, the item must be in the [`ItemTags#DYEABLE`][tag] and have the `DataComponents#DYED_COLOR` component set to some RGB value.
:::

The third parameter is a boolean that indicates whether the texture provided during rendering should be used instead of the one defined within the `Layer`. An example of this is a custom cape or custom elytra texture for the player.

Let's create an equipment info for the copper armor material. We'll also assume that for each layer there are two textures: one for the actual armor and one that is overlayed and tinted. For the animal armor, we'll say that there is some dynamic texture to be used that can be passed in.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// In assets/examplemod/equipment/copper.json
{
    // The layer map
    "layers": {
        // The serialized name of the EquipmentClientInfo.LayerType to apply.
        // For humanoid head, chest, and feet
        "humanoid": [
            // A list of layers to render in the order provided
            {
                // The relative texture of the armor
                // Points to assets/examplemod/textures/entity/equipment/humanoid/copper/outer.png
                "texture": "examplemod:copper/outer"
            },
            {
                // The overlay texture
                // Points to assets/examplemod/textures/entity/equipment/humanoid/copper/outer_overlay.png
                "texture": "examplemod:copper/outer_overlay",
                // When specified, allows the texture to be tinted the color in DataComponents#DYED_COLOR
                // Otherwise, cannot be tinted
                "dyeable": {
                    // An RGB value (always opaque color)
                    // 0x7683DE as decimal
                    // When not specified, set to 0 (meaning transparent or invisible)
                    "color_when_undyed": 7767006
                }
            }
        ],
        // For humanoid legs
        "humanoid_leggings": [
            {
                // Points to assets/examplemod/textures/entity/equipment/humanoid_leggings/copper/inner.png
                "texture": "examplemod:copper/inner"
            },
            {
                // Points to assets/examplemod/textures/entity/equipment/humanoid_leggings/copper/inner_overlay.png
                "texture": "examplemod:copper/inner_overlay",
                "dyeable": {
                    "color_when_undyed": 7767006
                }
            }
        ],
        // For wolf armor
        "wolf_body": [
            {
                // Points to assets/examplemod/textures/entity/equipment/wolf_body/copper/wolf.png
                "texture": "examplemod:copper/wolf",
                // When true, uses the texture passed into the layer renderer instead
                "use_player_texture": true
            }
        ],
        // For horse armor
        "horse_body": [
            {
                // Points to assets/examplemod/textures/entity/equipment/horse_body/copper/horse.png
                "texture": "examplemod:copper/horse",
                "use_player_texture": true
            }
        ],
        // For nautilus armor
        "nautilus_body": [
            {
                // Points to assets/examplemod/textures/entity/equipment/nautilus_body/copper/nautilus.png
                "texture": "examplemod:copper/nautilus",
                "use_player_texture": true
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
public class MyEquipmentInfoProvider extends EquipmentAssetProvider {

    public MyEquipmentInfoProvider(PackOutput output) {
        super(output);
    }

    @Override
    protected void registerModels(BiConsumer<ResourceKey<EquipmentAsset>, EquipmentClientInfo> output) {
        output.accept(
            // Must match Equippable#assetId
            COPPER_ASSET,
            EquipmentClientInfo.builder()
                // For humanoid head, chest, and feet
                .addLayers(
                    EquipmentClientInfo.LayerType.HUMANOID,
                    // Base texture
                    new EquipmentClientInfo.Layer(
                        // The relative texture of the armor
                        // Points to assets/examplemod/textures/entity/equipment/humanoid/copper/outer.png
                        Identifier.fromNamespaceAndPath("examplemod", "copper/outer"),
                        Optional.empty(),
                        false
                    ),
                    // Overlay texture
                    new EquipmentClientInfo.Layer(
                        // The overlay texture
                        // Points to assets/examplemod/textures/entity/equipment/humanoid/copper/outer_overlay.png
                        Identifier.fromNamespaceAndPath("examplemod", "copper/outer_overlay"),
                        // An RGB value (always opaque color)
                        // When not specified, set to 0 (meaning transparent or invisible)
                        Optional.of(new EquipmentClientInfo.Dyeable(Optional.of(0x7683DE))),
                        false
                    )
                )
                // For humanoid legs
                .addLayers(
                    EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS,
                    new EquipmentClientInfo.Layer(
                        // Points to assets/examplemod/textures/entity/equipment/humanoid_leggings/copper/inner.png
                        Identifier.fromNamespaceAndPath("examplemod", "copper/inner"),
                        Optional.empty(),
                        false
                    ),
                    new EquipmentClientInfo.Layer(
                        // Points to assets/examplemod/textures/entity/equipment/humanoid_leggings/copper/inner_overlay.png
                        Identifier.fromNamespaceAndPath("examplemod", "copper/inner_overlay"),
                        Optional.of(new EquipmentClientInfo.Dyeable(Optional.of(0x7683DE))),
                        false
                    )
                )
                // For wolf armor
                .addLayers(
                    EquipmentClientInfo.LayerType.WOLF_BODY,
                    // Base texture
                    new EquipmentClientInfo.Layer(
                        // Points to assets/examplemod/textures/entity/equipment/wolf_body/copper/wolf.png
                        Identifier.fromNamespaceAndPath("examplemod", "copper/wolf"),
                        Optional.empty(),
                        // When true, uses the texture passed into the layer renderer instead
                        true
                    )
                )
                // For horse armor
                .addLayers(
                    EquipmentClientInfo.LayerType.HORSE_BODY,
                    // Base texture
                    new EquipmentClientInfo.Layer(
                        // Points to assets/examplemod/textures/entity/equipment/horse_body/copper/horse.png
                        Identifier.fromNamespaceAndPath("examplemod", "copper/horse"),
                        Optional.empty(),
                        true
                    )
                )
                // For nautilus armor
                .addLayers(
                    EquipmentClientInfo.LayerType.NAUTILUS_BODY,
                    // Base texture
                    new EquipmentClientInfo.Layer(
                        // Points to assets/examplemod/textures/entity/equipment/nautilus_body/copper/nautilus.png
                        Identifier.fromNamespaceAndPath("examplemod", "copper/nautilus"),
                        Optional.empty(),
                        true
                    )
                )
                .build()
        );
    }
}

@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createProvider(MyEquipmentInfoProvider::new);
}
```

</TabItem>
</Tabs>

## Equipment Rendering

The equipment infos are rendered via the `EquipmentLayerRenderer` in the render function of an `EntityRenderer` or one of its `RenderLayer`s. `EquipmentLayerRenderer` is obtained as part of the render context via `EntityRendererProvider.Context#getEquipmentRenderer`. If the `EquipmentClientInfo`s are required, they are also available via `EntityRendererProvider.Context#getEquipmentAssets`.

By default, the following layers render the associated `EquipmentClientInfo.LayerType`:

| `LayerType`             | `RenderLayer`          | Used by                                                        |
|:-----------------------:|:----------------------:|:---------------------------------------------------------------|
| `HUMANOID`              | `HumanoidArmorLayer`   | Player, humanoid mobs (e.g., zombies, skeletons), armor stands |
| `HUMANOID_LEGGINGS`     | `HumanoidArmorLayer`   | Player, humanoid mobs (e.g., zombies, skeletons), armor stands |
| `HUMANOID_BABY`         | `HumanoidArmorLayer`   | Player, baby humanoid mobs (e.g. baby zombies)                 |
| `WINGS`                 | `WingsLayer`           | Player, humanoid mobs (e.g., zombies, skeletons), armor stands |
| `WOLF_BODY`             | `WolfArmorLayer`       | Wolf                                                           |
| `HORSE_BODY`            | `HorseArmorLayer`      | Horse                                                          |
| `LLAMA_BODY`            | `LlamaDecorLayer`      | Llama, trader llama                                            |
| `PIG_SADDLE`            | `SimpleEquipmentLayer` | Pig                                                            |
| `STRIDER_SADDLE`        | `SimpleEquipmentLayer` | Strider                                                        |
| `CAMEL_SADDLE`          | `SimpleEquipmentLayer` | Camel                                                          |
| `CAMEL_HUSK_SADDLE`     | `SimpleEquipmentLayer` | Camel husk                                                     |
| `HORSE_SADDLE`          | `SimpleEquipmentLayer` | Horse                                                          |
| `DONKEY_SADDLE`         | `SimpleEquipmentLayer` | Donkey                                                         |
| `MULE_SADDLE`           | `SimpleEquipmentLayer` | Mule                                                           |
| `ZOMBIE_HORSE_SADDLE`   | `SimpleEquipmentLayer` | Zombie Horse                                                   |
| `SKELETON_HORSE_SADDLE` | `SimpleEquipmentLayer` | Skeleton Horse                                                 |
| `HAPPY_GHAST_BODY`      | `SimpleEquipmentLayer` | Happy Ghast                                                    |
| `NAUTILUS_SADDLE`       | `SimpleEquipmentLayer` | Nautilus                                                       |
| `NAUTILUS_BODY`         | `SimpleEquipmentLayer` | Nautilus                                                       |

`EquipmentLayerRenderer` has only one method to submit the equipment layers for rendering: `renderLayers`.

```java
// In some render method where EquipmentLayerRenderer equipmentLayerRenderer is available
this.equipmentLayerRenderer.renderLayers(
    // The layer type to render
    EquipmentClientInfo.LayerType.HUMANOID,
    // The resource key representing the EquipmentClientInfo JSON
    // This would be set in the `EQUIPPABLE` data component via `assetId`
    stack.get(DataComponents.EQUIPPABLE).assetId().orElseThrow(),
    // The model to apply the equipment info to
    // These are usually separate models from the entity model
    // and are separate ModelLayers linking to a LayerDefinition
    model,
    // The item stack representing the item being rendered as a model
    // This is only used to get the dyeable, foil, and armor trim information
    stack,
    // The pose stack used to render the model in the correct location
    poseStack,
    // The collector to submit the model data to
    collector,
    // The packed light coordinates
    lightCoords,
    // An absolute path of the texture to render when use_player_texture is true for one of the layer if not null
    // Represents an absolute location within the assets folder
    Identifier.fromNamespaceAndPath("examplemod", "textures/other_texture.png"),
    // The color of the model outline
    // Only used if the outline color is not 0 and the `RenderType` has or is an outline type
    outlineColor,
    // The starting order priority to submit the layers and trims, ticking up with each model submitted
    // By default, this is 1
    order
);
```

[item]: index.md
[datacomponents]: datacomponents.md
[enchantment]: ../resources/server/enchantments/index.md#enchantment-costs-and-levels
[livingentity]: ../entities/livingentity.md
[registering]: ../concepts/registries.md#methods-for-registering
[rendering]: #equipment-rendering
[respack]: ../resources/index.md#assets
[tag]: ../resources/server/tags.md
[tinting]: ../resources/client/models/index.md#tinting

## items/consumables

---
sidebar_position: 3
---
# Consumables

Consumables are [items][item] which can be used over a period of time, 'consuming' them in the process. Anything that can be eaten or drunk in Minecraft is a consumable of some kind.

## The `Consumable` Data Component

Any item that can be consumed has the [`DataComponents#CONSUMABLE` component][datacomponent]. The backing record `Consumable` defines how the item is consumed and what effects to apply after consumption.

A `Consumable` can be created either by directly calling the record constructor or via `Consumable#builder`, which sets the defaults for each field, followed by `build` once finished:

- `consumeSeconds` - A `float` representing the number of seconds needed to fully consume the item. `Item#finishUsingItem` is called after the alloted time passes. Defaults to 1.6 seconds, or 32 ticks.
- `animation` - Sets the [`ItemUseAnimation`][animation] to play while the item is being used. Defaults to `ItemUseAnimation#EAT`.
- `sound` - Sets the [`SoundEvent`][sound] to play while consuming the item. This must be a `Holder` instance. Defaults to `SoundEvents#GENERIC_EAT`.
    - If a vanilla instance is not a `Holder<SoundEvent>`, a `Holder` wrapped version can be obtained by calling `BuiltInRegistries.SOUND_EVENT.wrapAsHolder(soundEvent)`.
- `soundAfterConsume` - Sets the [`SoundEvent`][sound] to player once the item has finished being consumed. This delegates to the [`PlaySoundConsumeEffect`][consumeeffect].
- `hasConsumeParticles` - When `true`, spawns item [particles] every four ticks and once the item is fully consumed. Defauts to `true`.
- `onConsume` - Adds a [`ConsumeEffect`][consumeeffect] to apply once the item has fully been consumed via `Item#finishUsingItem`.

Vanilla provides some consumables within their `Consumables` class, such as `#defaultFood` for [food] items and `#defaultDrink` for [potions] and milk buckets.

The `Consumable` component can be added by calling `Item.Properties#component`:

```java
// Assume there is some DeferredRegister.Items ITEMS
public static final DeferredItem<Item> CONSUMABLE = ITEMS.registerSimpleItem(
    "consumable",
    props -> props.component(
        DataComponents.CONSUMABLE,
        Consumable.builder()
            // Spend 2 seconds, or 40 ticks, to consume
            .consumeSeconds(2f)
            // Sets the animation to play while consuming
            .animation(ItemUseAnimation.BLOCK)
            // Play sound while consuming every tick
            .sound(SoundEvents.ARMOR_EQUIP_CHAIN)
            // Play sound once finished consuming
            .soundAfterConsume(SoundEvents.BREEZE_WIND_CHARGE_BURST)
            // Don't show particles while eating
            .hasConsumeParticles(false)
            .onConsume(
                // When finished consuming, applies the effects with a 30% chance
                new ApplyStatusEffectsConsumeEffect(new MobEffectInstance(MobEffects.HUNGER, 600, 0), 0.3F)
            )
            // Can have multiple
            .onConsume(
                // Teleports the entity randomly in a 50 block radius
                new TeleportRandomlyConsumeEffect(100f)
            )
            .build()
    )
);
```

### `ConsumeEffect`

When a consumable has finished being used, you may want to trigger some kind of logic to execute like adding a potion effect. These are handled by `ConsumeEffect`s, which are added to the `Consumable` by calling `Consumable.Builder#onConsume`.

A list of vanilla effects can be found in `ConsumeEffect`.

Every `ConsumeEffect` has two methods: `getType`, which specifies the registry object `ConsumeEffect.Type`; and `apply`, which is called on the item when it has been fully consumed. `apply` takes three arguments: the `Level` the consuming entity is in, the `ItemStack` the consumable was called on, and the `LivingEntity` consuming the object. When the effect is successfully applied, the method returns `true`, or `false` if it failed.

A `ConsumeEffect` can be created by implementing the interface and [registering] the `ConsumeEffect.Type` with the associated `MapCodec` and `StreamCodec` to `BuiltInRegistries#CONSUME_EFFECT_TYPE`:

```java
public record UsePortalConsumeEffect(ResourceKey<Level> level)
    implements ConsumeEffect, Portal {

    @Override
    public boolean apply(Level level, ItemStack stack, LivingEntity entity) {
        if (entity.canUsePortal(false)) {
            entity.setAsInsidePortal(this, entity.blockPosition());

            // Can successfully use portal
            return true;
        }

        // Cannot use portal
        return false;
    }

    @Override
    public ConsumeEffect.Type<? extends ConsumeEffect> getType() {
        // Set to registered object
        return USE_PORTAL.get();
    }

    @Override
    @Nullable
    public TeleportTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        // Set teleport location
    }
}

// In some registrar class
// Assume there is some DeferredRegister<ConsumeEffect.Type<?>> CONSUME_EFFECT_TYPES
public static final Supplier<ConsumeEffect.Type<UsePortalConsumeEffect>> USE_PORTAL =
    CONSUME_EFFECT_TYPES.register("use_portal", () -> new ConsumeEffect.Type<>(
        ResourceKey.codec(Registries.DIMENSION).optionalFieldOf("dimension")
            .xmap(UsePortalConsumeEffect::new, UsePortalConsumeEffect::level),
        ResourceKey.streamCodec(Registries.DIMENSION)
            .map(UsePortalConsumeEffect::new, UsePortalConsumeEffect::level)
    ));

// For some Item.Properties that is adding a CONSUMABLE component
Consumable.builder()
    .onConsume(
        new UsePortalConsumeEffect(Level.END)
    )
    .build();
```

### `ItemUseAnimation`

`ItemUseAnimation` is functionally an enum which doesn't define anything besides its id and name. Its uses are hardcoded into `ItemHandRenderer#renderArmWithItem` for first person and `AvatarRenderer#getArmPose` for third person. As such, simply creating a new `ItemUseAnimation` will only function similarly to `ItemUseAnimation#NONE`.

To apply some animation, you need to implement `IClientItemExtensions#applyForgeHandTransform` for first person and/or `IClientItemExtensions#getArmPose` for third person rendering.

#### Creating the `ItemUseAnimation`

First, let's create a new `ItemUseAnimation`. This is done using the [extensible enum][extensibleenum] system:

```json5
{
    "entries": [
        {
            "enum": "net/minecraft/world/item/ItemUseAnimation",
            "name": "EXAMPLEMOD_ITEM_USE_ANIMATION",
            "constructor": "(ILjava/lang/String;)V",
            "parameters": [
                // The id, should always be -1
                -1,
                // The name, should be a unique identifier
                "examplemod:item_use_animation"
            ]
        }
    ]
}
```

Then we can get the enum constant via `valueOf`:

```java
public static final ItemUseAnimation EXAMPLE_ANIMATION = ItemUseAnimation.valueOf("EXAMPLEMOD_ITEM_USE_ANIMATION");
```

From there, we can then start applying the transforms. To do this, we must create a new `IClientItemExtensions`, implement our desired methods, and register it via `RegisterClientExtensionsEvent` on the [**mod event bus**][modbus]:

```java
public class ConsumableClientItemExtensions implements IClientItemExtensions {
    // Implement methods here
}

// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
    event.registerItem(
        // The instance of the item extensions
        new ConsumableClientItemExtensions(),
        // A vararg of items that use this
        CONSUMABLE
    )
}
```

#### First Person

The first person transform, which all consumables have, is implemented via `IClientItemExtensions#applyForgeHandTransform`:

```java
public class ConsumableClientItemExtensions implements IClientItemExtensions {

    // ...

    @Override
    public boolean applyForgeHandTransform(
        PoseStack poseStack, LocalPlayer player, HumanoidArm arm, ItemStack itemInHand,
        float partialTick, float equipProcess, float swingProcess
    ) {
        // We first need to check if the item is being used and has our animation
        HumanoidArm usingArm = entity.getUsedItemHand() == InteractionHand.MAIN_HAND
            ? entity.getMainArm()
            : entity.getMainArm().getOpposite();
        if (
            entity.isUsingItem() && entity.getUseItemRemainingTicks() > 0
            && usingArm == arm && itemInHand.getUseAnimation() == EXAMPLE_ANIMATION
        ) {
            // Apply transformations to pose stack (translate, scale, mulPose)
            // ...
            return true;
        }

        // Do nothing
        return false;
    }
}
```

#### Third Person

The third person transforms, which all but `EAT` and `DRINK` have special logic for, is implemented via `IClientItemExtensions#getArmPose`, where `HumanoidModel.ArmPose` can also be extended for a custom transform.

As an `ArmPose` requries a lambda as part of its constructor, an `EnumProxy` reference must be used:

```json5
{
    "entries": [
        {
            "name": "EXAMPLEMOD_ITEM_USE_ANIMATION",
            // ...
        },
        {
            "enum": "net/minecraft/client/model/HumanoidModel$ArmPose",
            "name": "EXAMPLEMOD_ARM_POSE",
            "constructor": "(ZLnet/neoforged/neoforge/client/IArmPoseTransformer;)V",
            "parameters": {
                // Point to class where the proxy is located
                // Should be separate as this is a client only class
                "class": "example/examplemod/client/MyClientEnumParams",
                // The field name of the enum proxy
                "field": "CUSTOM_ARM_POSE"
            }
        }
    ]
}
```

```java
// Create the enum parameters
public class MyClientEnumParams {
    public static final EnumProxy<HumanoidModel.ArmPose> CUSTOM_ARM_POSE = new EnumProxy<>(
        HumanoidModel.ArmPose.class,
        // Whether the pose uses both arms
        false,
        // Whether the offhand location should be affected by the model pose
        false,
        // The pose transformer
        (IArmPoseTransformer) MyClientEnumParams::applyCustomModelPose
    );

    private static void applyCustomModelPose(
        HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm
    ) {
        // Apply model transforms here
        // ...
    }
}

// In some client only class
public static final HumanoidModel.ArmPose EXAMPLE_POSE = HumanoidModel.ArmPose.valueOf("EXAMPLEMOD_ARM_POSE");
```

Then, the arm pose is set via `IClientItemExtensions#getArmPose`:

```java
public class ConsumableClientItemExtensions implements IClientItemExtensions {

    // ...

    @Override
    public HumanoidModel.ArmPose getArmPose(
        LivingEntity entity, InteractionHand hand, ItemStack stack
    ) {
        // We first need to check if the item is being used and has our animation
        if (
            entity.isUsingItem() && entity.getUseItemRemainingTicks() > 0
            && entity.getUsedItemHand() == hand
            && itemInHand.getUseAnimation() == EXAMPLE_ANIMATION
        ) {
            // Return pose to apply
            return EXAMPLE_POSE;
        }

        // Otherwise return null
        return null;
    }
}
```

### Overriding Sounds on Entity

Sometimes, an entity may want to play a different sound while consuming an item. In those instances, the [`LivingEntity`][livingentity] instance can implement `Consumable.OverrideConsumeSound` and have `getConsumeSound` return the `SoundEvent` they want their entity to play.

```java
public class MyEntity extends LivingEntity implements Consumable.OverrideConsumeSound {
    
    // ...

    @Override
    public SoundEvent getConsumeSound(ItemStack stack) {
        // Return the sound to play
    }
}
```

## `ConsumableListener`

While consumables and effects that are applied after consumption are useful, sometimes the properties of an effect need to be externally available as other [data components][datacomponents]. For example, cats and wolves also eat [food] and query its nutrition, or item with potion contents query its color for rendering. In these instances, data components implement `ConsumableListener` to provide consumption logic.

A `ConsumableListener` only has one method: `#onConsume`, which takes in the current level, the entity consuming the item, the item being consumed, and the `Consumable` instance on the item. `onConsume` is called during `Item#finishUsingItem` when the item has been fully consumed.

Adding your own `ConsumableListener` is simply [registering a new data component][datacompreg] and implementing `ConsumableListener`.

```java
public record MyConsumableListener() implements ConsumableListener {

    @Override
    public void onConsume(
        Level level, LivingEntity entity, ItemStack stack, Consumable consumable
    ) {
        // Do things here
    }
}
```

### Food

Food is one type of `ConsumableListener` that is part of the hunger system. All of the functionality for food items is already handled within the `Item` class, so simply adding the `FoodProperties` to `DataComponents#FOOD` along with a consumable is all that's needed. There is a helper method called `food` which takes in the `FoodProperties` and the `Consumable` object, or `Consumables#DEFAULT_FOOD` if none is specified.

`FoodProperties` can be created either by directly calling the record constructor or via `new FoodProperties.Builder()`, followed by `build` once finished:

- `nutrition` - Sets how many hunger points are restored. Counts in half hunger points, so for example, Minecraft's steak restores 8 hunger points.
- `saturationModifier` - The saturation modifier used in calculating the [saturation value][hunger] restored when eating this food. The calculation is `min(2 * nutrition * saturationModifier, playerNutrition)`, meaning that using `0.5` will make the effective saturation value the same as the nutrition value.
- `alwaysEdible` - Whether this item can always be eaten, even if the hunger bar is full. `false` by default, `true` for golden apples and other items that provide bonuses beyond just filling the hunger bar.

```java
// Assume there is some DeferredRegister.Items ITEMS
public static final DeferredItem<Item> FOOD = ITEMS.registerSimpleItem(
    "food",
    props -> props.food(
        new FoodProperties.Builder()
            // Heals 1.5 hearts
            .nutrition(3)
            // Carrot is 0.3
            // Raw Cod is 0.1
            // Cooked Chicken is 0.6
            // Cooked Beef is 0.8
            // Golden Aple is 1.2
            .saturationModifier(0.3f)
            // When set, the food can alway be eaten even with
            //  a full hunger bar.
            .alwaysEdible()
    )
);
```

For examples, or to look at the various values used by Minecraft, have a look at the `Foods` class.

To get the `FoodProperties` for an item, call `ItemStack.get(DataComponents.FOOD)`. This may return null, since not every item is edible. To determine whether an item is edible, null-check the result of the `getFoodProperties` call.

### Potion Contents

The contents of a [potion][potions] via `PotionContents` is another `ConsumableListener` whose effects are applied on consumption. They contain an optional potion to apply, an optional tint for the potion color, a list of custom [`MobEffectInstance`s][mobeffectinstance] to apply alongside the potion, and an optional translation key to use when getting the stack name. The modder needs to override `Item#getName` if not a subtype of `PotionItem`.

[animation]: #itemuseanimation
[consumeeffect]: #consumeeffect
[datacomponent]: datacomponents.md
[datacompreg]: datacomponents.md#creating-custom-data-components
[extensibleenum]: ../advanced/extensibleenums.md
[food]: #food
[hunger]: https://minecraft.wiki/w/Hunger#Mechanics
[item]: index.md
[livingentity]: ../entities/livingentity.md
[modbus]: ../concepts/events.md#event-buses
[mobeffectinstance]: mobeffects.md#mobeffectinstances
[particles]: ../resources/client/particles.md
[potions]: mobeffects.md#potions
[sound]: ../resources/client/sounds.md#creating-soundevents
[registering]: ../concepts/registries.md#methods-for-registering

## items/datacomponents

---
sidebar_position: 2
---

# Data Components

Data components are key-value pairs within a map used to store data on the `Holder` of a registry object. Each piece of data, such as firework explosions or tools, are stored as actual objects on the holder, making the values visible and operable without having to dynamically transform a general encoded instance (e.g., `CompoundTag`, `JsonElement`).

## `DataComponentType`

Each data component has an associated `DataComponentType<T>`, where `T` is the component value type. The `DataComponentType` represents a key to reference the stored component value along with some codecs to handle reading and writing to the disk and network, if desired.

A list of existing components can be found within `DataComponents`.

### Creating Custom Data Components

The component value associated with the `DataComponentType` must implement `hashCode` and `equals` and should be considered **immutable** when stored.

:::note
Component values can very easily be implemented using a record. Record fields are immutable and implement `hashCode` and `equals`.
:::

```java
// A record example
public record ExampleRecord(int value1, boolean value2) {}

// A class example
public class ExampleClass {

    private final int value1;
    // Can be mutable, but care needs to be taken when using
    private boolean value2;

    public ExampleClass(int value1, boolean value2) {
        this.value1 = value1;
        this.value2 = value2;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.value1, this.value2);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else {
            return obj instanceof ExampleClass ex
                && this.value1 == ex.value1
                && this.value2 == ex.value2;
        }
    }
}
```

A standard `DataComponentType` can be created via `DataComponentType#builder` and built using `DataComponentType.Builder#build`. The builder contains four settings: `persistent`, `networkSynchronized`, `cacheEncoding`, and `ignoreSwapAnimation`.

`persistent` specifies the [`Codec`][codec] used to read and write the component value to disk. `networkSynchronized` specifies the `StreamCodec` used to read and write the component across the network. If `networkSynchronized` is not specified, then the `Codec` provided in `persistent` will be wrapped and used as the [`StreamCodec`][streamcodec].

:::warning
Either `persistent` or `networkSynchronized` must be provided in the builder; otherwise, a `NullPointerException` will be thrown. If no data should be sent across the network, then set `networkSynchronized` to `StreamCodec#unit`, providing the default component value.
:::

`cacheEncoding` caches the encoding result of the `Codec` such that any subsequent encodes uses the cached value if the component value hasn't changed. This should only be used if the component value is expected to rarely or never change.

`ignoreSwapAnimation` will cancel the swap animation if an item has this component on it. If this is not set, the animation could still be canceled depending on the client item properties.

`DataComponentType` are registry objects and must be [registered].

```java
// Using ExampleRecord(int, boolean)
// Only one Codec and/or StreamCodec should be used below
// Multiple are provided for an example

// Basic codec
public static final Codec<ExampleRecord> BASIC_CODEC = RecordCodecBuilder.create(instance ->
    instance.group(
        Codec.INT.fieldOf("value1").forGetter(ExampleRecord::value1),
        Codec.BOOL.fieldOf("value2").forGetter(ExampleRecord::value2)
    ).apply(instance, ExampleRecord::new)
);
public static final StreamCodec<ByteBuf, ExampleRecord> BASIC_STREAM_CODEC = StreamCodec.composite(
    ByteBufCodecs.INT, ExampleRecord::value1,
    ByteBufCodecs.BOOL, ExampleRecord::value2,
    ExampleRecord::new
);

// Unit stream codec if nothing should be sent across the network
public static final StreamCodec<ByteBuf, ExampleRecord> UNIT_STREAM_CODEC = StreamCodec.unit(new ExampleRecord(0, false));

// In another class
// The specialized DeferredRegister.DataComponents simplifies data component registration and avoids some generic inference issues with the `DataComponentType.Builder` within a `Supplier`
public static final DeferredRegister.DataComponents REGISTRAR = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "examplemod");

public static final Supplier<DataComponentType<ExampleRecord>> BASIC_EXAMPLE = REGISTRAR.registerComponentType(
    "basic",
    builder -> builder
        // The codec to read/write the data to disk
        .persistent(BASIC_CODEC)
        // The codec to read/write the data across the network
        .networkSynchronized(BASIC_STREAM_CODEC)
);

/// Component will not be saved to disk
public static final Supplier<DataComponentType<ExampleRecord>> TRANSIENT_EXAMPLE = REGISTRAR.registerComponentType(
    "transient",
    builder -> builder.networkSynchronized(BASIC_STREAM_CODEC)
);

// No data will be synced across the network
public static final Supplier<DataComponentType<ExampleRecord>> NO_NETWORK_EXAMPLE = REGISTRAR.registerComponentType(
   "no_network",
   builder -> builder
        .persistent(BASIC_CODEC)
        // Note we use a unit stream codec here
        .networkSynchronized(UNIT_STREAM_CODEC)
);
```

## The Component Map

All data components are stored within a `DataComponentMap`, using the `DataComponentType` as the key and the object as the value. `DataComponentMap` functions similarly to a read-only `Map`. As such, there are methods to `#get` an entry given its `DataComponentType` or provide a default if not present (via `#getOrDefault`).

For a registry object, the `DataComponentMap` can be obtained through `Holder#components`.

```java
// For some Item item

// Will get dye color if component is present
// Otherwise null
@Nullable
DyeColor color = item.builtInRegistryHolder().components().get(DataComponents.BASE_COLOR);
```

### `PatchedDataComponentMap`

As the default `DataComponentMap` only provides methods for read-based operations, write-based operations are supported using the subclass `PatchedDataComponentMap`. This includes `#set`ting the value of a component or `#remove`ing it altogether.

`PatchedDataComponentMap` stores changes using a prototype and patch map. The prototype is a `DataComponentMap` that contains the default components and their values this map should have. The patch map is a map of `DataComponentType`s to `Optional` values that contain the changes made to the default components.

```java
// For some PatchedDataComponentMap map

// Sets the base color to white
map.set(DataComponents.BASE_COLOR, DyeColor.WHITE);

// Removes the base color by
// - Removing the patch if no default is provided
// - Setting an empty optional if there is a default
map.remove(DataComponents.BASE_COLOR);
```

:::danger
Both the prototype and patch map are part of the hash code for the `PatchedDataComponentMap`. As such, any component values within the map should be treated as **immutable**. Always call `#set` or one of its referring methods discussed below after modifying the value of a data component.
:::

## The Component Getter

All instances that can provide data components usually implement `DataComponentGetter`. `DataComponentGetter` effectively gets the component values for a data type, either through a backing map or creating the value on the fly.

## The Component Holder

All instances that reference the backing data component map implement `DataComponentHolder`, which extends `DataComponentGetter`. `DataComponentHolder` is effectively a delegate to the read-only methods within `DataComponentMap`.

```java
// For some DataComponentHolder holder

// Delegates to 'DataComponentMap#get'
@Nullable
DyeColor color = holder.get(DataComponents.BASE_COLOR);
```

### `MutableDataComponentHolder`

`MutableDataComponentHolder` is an interface provided by NeoForge to support write-based methods to the component map. All implementations within Vanilla and NeoForge store data components using a `PatchedDataComponentMap`, so the `#set` and `#remove` methods also have delegates with the same name.

In addition, `MutableDataComponentHolder` also provides an `#update` method which handles getting the component value or the provided default if none is set, operating on the value, and then setting it back to the map. The operator is either a `UnaryOperator`, which takes in the component value and returns the component value, or a `BiFunction`, which takes in the component value and another object and returns the component value.

```java
// For some ItemStack stack

FireworkExplosion explosion = stack.get(DataComponents.FIREWORK_EXPLOSION);

// Modifying the component value
explosion = explosion.withFadeColors(new IntArrayList(new int[] {1, 2, 3}));

// Since we modified the component value, 'set' should be called afterward
stack.set(DataComponents.FIREWORK_EXPLOSION, explosion);

// Update the component value (calls 'set' internally)
stack.update(
    DataComponents.FIREWORK_EXPLOSION,
    // Default value if no component value is present
    FireworkExplosion.DEFAULT,
    // Return a new FireworkExplosion to set
    explosion -> explosion.withFadeColors(new IntArrayList(new int[] {4, 5, 6}))
);

stack.update(
    DataComponents.FIREWORK_EXPLOSION,
    // Default value if no component value is present
    FireworkExplosion.DEFAULT,
    // An object that is supplied to the function
    new IntArrayList(new int[] {7, 8, 9}),
    // Return a new FireworkExplosion to set
    FireworkExplosion::withFadeColors
);
```

## Adding Default Data Components to Items

Although the mutable data components are stored on an `ItemStack`, a map of default components can be set through `Item`, to be stored on the `Holder<Item>` and finally passed to the `ItemStack` as a prototype when constructed. A component can be added to the `Item` via `Item.Properties#component`. For components that rely on dynamically generated data, such as [datapack registry objects][datapackregistry], `Item.Properties#delayedComponent` should be used instead, constructing the value given the `HolderLookup.Provider` of registries.

```java
// For some DeferredRegister.Items REGISTRAR
public static final Item COMPONENT_EXAMPLE = REGISTRAR.register("component",
    // register is used over other overloads as the DataComponentType has not been registered yet
    registryName -> new Item(
        new Item.Properties()
        .setId(ResourceKey.create(Registries.ITEM, registryName))
        // Passes in the direct component value.
        .component(BASIC_EXAMPLE.get(), new ExampleRecord(24, true))
        // Passes in a component factory, taking in the registry context and returning the value.
        .delayedComponent(DataComponents.DAMAGE_RESISTANT, context -> new DamageResistant(context.getOrThrow(DamageTypeTags.IS_EXPLOSION)))
    )
);
```

If the data component should be added to an existing item that belongs to Vanilla or another mod, then `ModifyDefaultComponentsEvent` should be listened for on the [**mod event bus**][modbus]. The event provides the `modify` and `modifyMatching` methods which allows the `DataComponentPatch.Builder` to be modified for the associated items. The builder can `#set` existing components, including `#set`ting them to null which effectively removes them.

```java
@SubscribeEvent // on the mod event bus
public static void modifyComponents(ModifyDefaultComponentsEvent event) {
    // Sets the component on melon seeds
    event.modify(Items.MELON_SEEDS, builder ->
        builder.set(BASIC_EXAMPLE.get(), new ExampleRecord(10, false))
    );

    // Removes the component for any items that have a crafting remainder
    event.modifyMatching(
        (item, components) -> item.getCraftingRemainder() != null,
        builder -> builder.set(DataComponents.BUCKET_ENTITY_DATA, null)
    );
}
```

## Using Custom Component Holders

To create a custom data component holder, the holder object simply needs to implement `MutableDataComponentHolder` and implement the missing methods. The holder object must contain a field representing the `PatchedDataComponentMap` to implement the associated methods.

```java
public class ExampleHolder implements MutableDataComponentHolder {

    private int data;
    private final PatchedDataComponentMap components;

    // Overloads can be provided to supply the map itself
    public ExampleHolder() {
        this.data = 0;
        this.components = new PatchedDataComponentMap(DataComponentMap.EMPTY);
    }

    @Override
    public DataComponentMap getComponents() {
        return this.components;
    }

    @Nullable
    @Override
    public <T> T set(DataComponentType<? super T> componentType, @Nullable T value) {
        return this.components.set(componentType, value);
    }

    @Nullable
    @Override
    public <T> T remove(DataComponentType<? extends T> componentType) {
        return this.components.remove(componentType);
    }

    @Override
    public void applyComponents(DataComponentPatch patch) {
        this.components.applyPatch(patch);
    }

    @Override
    public void applyComponents(DataComponentMap components) {
        this.components.setAll(components);
    }

    // Other methods
}
```

### `DataComponentPatch` and Codecs

To persist components to disk or send information across the network, the holder could send the entire `DataComponentMap`. However, this is generally a waste of information as any defaults will already be present wherever the data is sent to. So, instead, we use a `DataComponentPatch` to send the associated data. `DataComponentPatch`es only contain the patch information of the component map without any defaults. The patches are then applied to the prototype in the receiver's location.

A `DataComponentPatch` can be created from a `PatchedDataComponentMap` via `#patch`. Likewise, `PatchedDataComponentMap#fromPatch` can construct a `PatchedDataComponentMap` given the prototype `DataComponentMap` and a `DataComponentPatch`.

```java
public class ExampleHolder implements MutableDataComponentHolder {

    public static final Codec<ExampleHolder> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.INT.fieldOf("data").forGetter(ExampleHolder::getData),
            DataCopmonentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(holder -> holder.components.asPatch())
        ).apply(instance, ExampleHolder::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ExampleHolder> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, ExampleHolder::getData,
        DataComponentPatch.STREAM_CODEC, holder -> holder.components.asPatch(),
        ExampleHolder::new
    );

    // ...

    public ExampleHolder(int data, DataComponentPatch patch) {
        this.data = data;
        this.components = PatchedDataComponentMap.fromPatch(
            // The prototype map to apply to
            DataComponentMap.EMPTY,
            // The associated patches
            patch
        );
    }

    // ...
}
```

[Syncing the holder data across the network][network] and reading/writing the data to disk must be done manually.

[datapackregistry]: ../concepts/registries.md#datapack-registries
[registered]: ../concepts/registries.md
[codec]: ../datastorage/codecs.md
[modbus]: ../concepts/events.md#event-buses
[network]: ../networking/payload.md
[streamcodec]: ../networking/streamcodecs.md

## items/index

# Items

Along with blocks, items are a key component of Minecraft. While blocks make up the world around you, items exist within inventories.

## What Even Is an Item?

Before we get further into creating items, it is important to understand what an item actually is, and what distinguishes it from, say, a [block][block]. Let's illustrate this using an example:

- In the world, you encounter a dirt block and want to mine it. This is a **block**, because it is placed in the world. (Actually, it is not a block, but a blockstate. See the [Blockstates article][blockstates] for more detailed information.)
    - Not all blocks drop themselves when breaking (e.g. leaves), see the article on [loot tables][loottables] for more information.
- Once you have [mined the block][breaking], it is removed (= replaced with an air block) and the dirt drops. The dropped dirt is an item **[entity][entity]**. This means that like other entities (pigs, zombies, arrows, etc.), it can inherently be moved by things like water pushing on it, or burned by fire and lava.
- Once you pick up the dirt item entity, it becomes an **item stack** in your inventory. An item stack is, simply put, an instance of an item with some extra information, such as the stack size.
- Item stacks are backed by their corresponding **item** (which is what we're creating). Items hold [data components][datacomponents] that contains the default information all items stacks are initialized to (for example, every iron sword has a max durability of 250), while item stacks can modify those data components, allowing two different stacks for the same item to have different information (for example, one iron sword has 100 uses left, while another iron sword has 200 uses left). For more information on what is done through items and what is done through item stacks, read on.
    - The relationship between items and item stacks is roughly the same as between [blocks][block] and [blockstates][blockstates], in that a blockstate is always backed by a block. It's not a really accurate comparison (item stacks aren't singletons, for example), but it gives a good basic idea about what the concept is here.

## Creating an Item

Now that we understand what an item is, let's create one!

Like with basic blocks, for basic items that need no special functionality (think sticks, sugar, etc.), the `Item` class can be used directly. To do so, during registration, instantiate `Item` with a `Item.Properties` parameter. This `Item.Properties` parameter can be created using `Item.Properties#of`, and it can be customized by calling its methods:

- `setId` - Sets the resource key of the item.
    - This **must** be set on every item; otherwise, an exception will be thrown.
- `overrideDescription` - Sets the translation key of the item. The created `Component` is stored in `DataComponents#ITEM_NAME`.
- `useBlockDescriptionPrefix` - Convenience helper that calls `overrideDescription` with the translation key `block.<modid>.<registry_name>`. This should be called on any `BlockItem`.
- `requiredFeatures` - Sets the required feature flags for this item. This is mainly used for vanilla's feature locking system in minor versions. It is discouraged to use this, unless you're integrating with a system locked behind feature flags by vanilla.
- `stacksTo` - Sets the max stack size (via `DataComponents#MAX_STACK_SIZE`) of this item. Defaults to 64. Used e.g. by ender pearls or other items that only stack to 16.
- `durability` - Sets the durability (via `DataComponents#MAX_DAMAGE`) of this item and the initial damage to 0 (via `DataComponents#DAMAGE`). Defaults to 0, which means "no durability". For example, iron tools use 250 here. Note that setting the durability automatically locks the max stack size to 1.
- `fireResistant` - Makes item entities that use this item immune to fire and lava (via `DataComponents#FIRE_RESISTANT`). Used by various netherite items.
- `rarity` - Sets the rarity of this item (via `DataComponents#RARITY`). Currently, this simply changes the item's color. `Rarity` is an enum consisting of the four values `COMMON` (white, default), `UNCOMMON` (yellow), `RARE` (aqua) and `EPIC` (light purple). Be aware that mods may add more rarity types.
- `setNoCombineRepair` - Disables grindstone and crafting grid repairing for this item. Unused in vanilla.
- `jukeboxPlayable` - Sets the resource key of the datapack `JukeboxSong` to play when inserted into a jukebox.
- `food` - Sets the [`FoodProperties`][food] of this item (via `DataComponents#FOOD`).

For examples, or to look at the various values used by Minecraft, have a look at the `Items` class.

### Remainders and Cooldowns

Items may have additional properties that are applied when being used or prevent the item from being used for a set time:

- `craftRemainder` - Sets the crafting remainder of this item. Vanilla uses this for filled buckets that leave behind empty buckets after crafting.
- `usingConvertsTo` - Sets the item to return after the item is finished being used via `Item#use`, `IItemExtension#finishUsingItem`, or `Item#releaseUsing`. The `ItemStack` is stored on `DataComponents#USE_REMAINDER`.
- `useCooldown` - Sets the number of seconds before the item can be used again (via `DataComponents#USE_COOLDOWN`).

### Tools and Armor

Some items act like [tools] and [armor]. These are constructed via a series of item properties, with only some usage being delegated to their associated classes:

- `enchantable` - Sets the maximum [enchantment] value of the stack, allowing the item to be enchanted (via `DataComponents#ENCHANTABLE`).
- `repairable` - Sets the item or tag that can be used to repair the durability of this item (via `DataComponents#REPAIRABLE`). Must have durability components and not `DataComponents#UNBREAKABLE`.
- `equippable` - Sets the slot the item can be equipped to (via `DataComponents#EQUIPPABLE`).
- `equippableUnswappable` - Same as `equippable`, but disables quick swapping via the use item button (default right-click).

More information can be found on their relevant pages.

### More Functionality

Directly using `Item` only allows for very basic items. If you want to add functionality, for example right-click interactions, a custom class that extends `Item` is required. The `Item` class has many methods that can be overridden to do different things; see the classes `Item` and `IItemExtension` for more information.

The two most common use cases for items are left-clicking and right-clicking. Due to their complexity and their reaching into other systems, they are explained in a separate [Interaction article][interactions].

### `DeferredRegister.Items`

All registries use `DeferredRegister` to register their contents, and items are no exceptions. However, due to the fact that adding new items is such an essential feature of an overwhelming amount of mods, NeoForge provides the `DeferredRegister.Items` helper class that extends `DeferredRegister<Item>` and provides some item-specific helpers:

```java
public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ExampleMod.MOD_ID);

public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerItem(
    "example_item",
    Item::new, // The factory that the properties will be passed into.
    props -> props // A unary operator of the properties to use.
);
```

Internally, this will simply call `ITEMS.register("example_item", registryName -> new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, registryName))))` by applying the properties parameter to the provided item factory (which is commonly the constructor). The id is set on the properties.

If you want to use `Item::new`, you can leave out the factory entirely and use the `simple` method variant:

```java
public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem(
    "example_item",
    props -> props // A unary operator of the properties to use.
);
```

This does the exact same as the previous example, but is slightly shorter. Of course, if you want to use a subclass of `Item` and not `Item` itself, you will have to use the previous method instead.

Both of these methods also have overloads that omit the `new Item.Properties()` parameter:

```java
public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerItem("example_item", Item::new);

// Variant that also omits the Item::new parameter
public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item");
```

Finally, there's also shortcuts for block items. Along with `setId`, these also call `useBlockDescriptionPrefix` to set the translation key to the one used for a block:

```java
public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(
    "example_block",
    ExampleBlocksClass.EXAMPLE_BLOCK,
    props -> props
);

// Variant that omits the properties parameter:
public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(
    "example_block",
    ExampleBlocksClass.EXAMPLE_BLOCK
);

// Variant that omits the name parameter, instead using the block's registry name:
public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(
    // Must be an instance of `Holder<Block>`
    // DeferredBlock<T> also works
    ExampleBlocksClass.EXAMPLE_BLOCK,
    props -> props
);

// Variant that omits both the name and the properties:
public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(
    // Must be an instance of `Holder<Block>`
    // DeferredBlock<T> also works
    ExampleBlocksClass.EXAMPLE_BLOCK
);
```

:::note
If you keep your registered blocks in a separate class, you should classload your blocks class before your items class.
:::

### Resources

If you register your item and get your item (via `/give` or through a [creative tab][creativetabs]), you will find it to be missing a proper model and texture. This is because textures and models are handled by Minecraft's resource system.

For every item, you will want to add - or [generate][datagen] - JSON files for the following:

- A [client item][citems] with an associated [texture]
- A [translation][i18n]
- A [recipe][recipes] (optional)
- Some item [tags] (optional)

For all of the above, also reference the files and data generators of similar vanilla blocks.

## `ItemStack`s

Like with blocks and blockstates, most places where you'd expect an `Item` actually use an `ItemStack` instead. `ItemStack`s represent a stack of one or multiple items in a container, e.g. an inventory. Again like with blocks and blockstates, methods should be overridden by the `Item` and called on the `ItemStack`, and many methods in `Item` get an `ItemStack` instance passed in.

An `ItemStack` consists of three major parts:

- The `Item` it represents, obtainable through `ItemStack#getItem`, or `getItemHolder` for `Holder<Item>`.
- The stack size, typically between 1 and 64, obtainable through `getCount` and changeable through `setCount` or `shrink`.
- The [data components][datacomponents] map, where stack-specific data is stored. Obtainable through `getComponents`. The components values are typically accessed and mutated via `has`, `get`, `set`, `update`, and `remove`.

To create a new `ItemStack`, call `new ItemStack(Item)`, passing in the backing item. By default, this uses a count of 1 and no NBT data; there are constructor overloads that accept a count and NBT data as well if needed. Note that an `ItemStack` cannot exist until components are bound/until a level exists. Until then, you should use an `ItemStackTemplate` as detailed below.

`ItemStack`s are mutable objects (see below), however it is sometimes required to treat them as immutables. If you need to modify an `ItemStack` that is to be treated immutable, you can clone the stack using `#copy` or `#copyWithCount` if a specific stack size should be used.

If you want to represent that a stack has no item, use `ItemStack.EMPTY`. If you want to check whether an `ItemStack` is empty, call `#isEmpty`.

### Mutability of `ItemStack`s

`ItemStack`s are mutable objects. This means that if you call for example `#setCount` or any data component map methods, the `ItemStack` itself will be modified. Vanilla uses the mutability of `ItemStack`s extensively, and several methods rely on it. For example, `#split` splits the given amount off the stack it is called on, both modifying the caller and returning a new `ItemStack` in the process.

However, this can sometimes lead to issues when dealing with multiple `ItemStack`s at once. The most common instance where this arises is when handling inventory slots, since you have to consider both the `ItemStack` currently selected by the cursor, as well as the `ItemStack` you are trying to insert to/extract from.

:::tip
When in doubt, better be safe than sorry and `#copy` the stack.
:::

## `ItemStackTemplate`s

`ItemStackTemplate`s are the immutable form of `ItemStack`s, typically representing a stack within an immutable context, such as recipes. Templates contain the basic elements that make up an `ItemStack`: the held holder `Item`, the stack size, and the [data components][datacomponents] the item has, stored as a patch.

To create a new `ItemStackTemplate`, call one of the `new ItemStackTemplate(...)` methods, passing in the `Item` and any other desired elements. Then, when a stack is needed, an `ItemStack` can be created via `ItemStackTemplate#create`.

### JSON Representation

In many situations, for example [recipes], `ItemStackTemplate`s need to be represented as JSON objects. An item stack template's JSON representation looks the following way:

```json5
{
    // The item ID. Required.
    "id": "minecraft:dirt",
    // The item stack count [1, 99]. Optional, defaults to 1.
    "count": 4,
    // A map of data components. Optional, defaults to an empty map.
    "components": {
        "minecraft:enchantment_glint_override": true
    }
}
```

## `ItemInstance`

`ItemInstance` is a superinterface that `ItemStack` and `ItemStackTemplate` implement. Generally, `ItemStack` and `ItemStackTemplate`s are used in isolated contexts. However, when the stack and template can be used interchangeably (e.g. the number of items in the stack / template), the `ItemInstance` superinterface is provided instead of a specific type.

`ItemInstance` provides common methods for checking the `Item` (`#is`), the stack size (`count`), and reading the data components through the `DataComponentGetter`.

## Creative Tabs

By default, your item will only be available through `/give` and not appear in the creative inventory. Let's change that!

The way you get your item into the creative menu depends on what tab you want to add it to.

### Existing Creative Tabs

:::note
This method is for adding your items to Minecraft's tabs, or to other mods' tabs. To add items to your own tabs, see below.
:::

An item can be added to an existing `CreativeModeTab` via the `BuildCreativeModeTabContentsEvent`, which is fired on the [mod event bus][modbus], only on the [logical client][sides]. Add items by calling `event#accept`.

```java
//MyItemsClass.MY_ITEM is a Supplier<? extends Item>, MyBlocksClass.MY_BLOCK is a Supplier<? extends Block>
@SubscribeEvent // on the mod event bus
public static void buildContents(BuildCreativeModeTabContentsEvent event) {
    // Is this the tab we want to add to?
    if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
        event.accept(MyItemsClass.MY_ITEM.get());
        // Accepts an ItemLike. This assumes that MY_BLOCK has a corresponding item.
        event.accept(MyBlocksClass.MY_BLOCK.get());
    }
}
```

The event also provides some extra information, such as `getFlags` to get the list of enabled feature flags, or `hasPermissions` to check if the player has permissions to view the operator items tab.

### Custom Creative Tabs

`CreativeModeTab`s are a registry, meaning custom `CreativeModeTab`s must be [registered][registering]. Creating a creative tab uses a builder system, the builder is obtainable through `CreativeModeTab#builder`. The builder provides options to set the title, icon, default items, and a number of other properties. In addition, NeoForge provides additional methods to customize the tab's image, label and slot colors, where the tab should be ordered, etc.

```java
//CREATIVE_MODE_TABS is a DeferredRegister<CreativeModeTab>
public static final Supplier<CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example", () -> CreativeModeTab.builder()
    //Set the title of the tab. Don't forget to add a translation!
    .title(Component.translatable("itemGroup." + MOD_ID + ".example"))
    //Set the icon of the tab.
    .icon(() -> new ItemStack(MyItemsClass.EXAMPLE_ITEM.get()))
    //Add your items to the tab.
    .displayItems((params, output) -> {
        output.accept(MyItemsClass.MY_ITEM.get());
        // Accepts an ItemLike. This assumes that MY_BLOCK has a corresponding item.
        output.accept(MyBlocksClass.MY_BLOCK.get());
    })
    .build()
);
```

## `ItemLike`

`ItemLike` is an interface implemented by `Item`s and [`Block`s][block] in vanilla. It defines the method `#asItem`, which returns an item representation of whatever the object actually is: `Item`s just return themselves, while `Block`s return their associated `BlockItem` if available, and `Blocks.AIR` otherwise. `ItemLike`s are used in various contexts where the "origin" of the item isn't important, for example in many [data generators][datagen].

It is also possible to implement `ItemLike` on your custom objects. Simply override `#asItem` and you're good to go.

[armor]: armor.md
[block]: ../blocks/index.md
[blockstates]: ../blocks/states.md
[breaking]: ../blocks/index.md#breaking-a-block
[citems]: ../resources/client/models/items.md
[creativetabs]: #creative-tabs
[datacomponents]: datacomponents.md
[datagen]: ../resources/index.md#data-generation
[enchantment]: ../resources/server/enchantments/index.md#enchantment-costs-and-levels
[entity]: ../entities/index.md
[food]: consumables.md#food
[hunger]: https://minecraft.wiki/w/Hunger#Mechanics
[interactions]: interactions.md
[loottables]: ../resources/server/loottables/index.md
[modbus]: ../concepts/events.md#event-buses
[recipes]: ../resources/server/recipes/index.md
[registering]: ../concepts/registries.md#methods-for-registering
[sides]: ../concepts/sides.md
[tools]: tools.md
[datagen]: ../resources/index.md#data-generation
[i18n]: ../resources/client/i18n.md
[texture]: ../resources/client/textures.md
[tags]: ../resources/server/tags.md

## items/interactions

---
sidebar_position: 1
---
# Interactions

This page aims to make the fairly complex and confusing process of things being left-clicked, right-clicked or middle-clicked by the player more understandable, as well as clarifying what result to use where and why.

## `HitResult`s

For the purpose of determining what the player is currently looking at, Minecraft uses a `HitResult`. A `HitResult` is somewhat equivalent to a ray cast result in other game engines, and most notably contains a method `#getLocation`.

A hit result can be of one of three types, represented through the `HitResult.Type` enum: `BLOCK`, `ENTITY`, or `MISS`. A `HitResult` of type `BLOCK` can be cast to `BlockHitResult`, while a `HitResult` of type `ENTITY` can be cast to `EntityHitResult`; both types provide additional context about what [block] or [entity] was hit. If the type is `MISS`, this indicates that neither a block nor an entity was hit, and should not be cast to either subclass.

Every frame on the [physical client][physicalside], the `Minecraft` class updates and stores the currently looked-at `HitResult` in the `hitResult` field. This field can then be accessed through `Minecraft.getInstance().hitResult`.

## Left-Clicking an Item

- It is checked that all required [feature flags][featureflag] for the [`ItemStack`][itemstack] in your main hand are enabled. If this check fails, the pipeline ends.
- If `Player#cannotAttackWithItem`, which checks the attack delay and `DataComponents#MINIMUM_ATTACK_CHARGE`, returns false, the pipeline ends.
- `InputEvent.InteractionKeyMappingTriggered` is fired with the left mouse button and the main hand. If the [event][event] is [canceled][cancel], the pipeline ends.
- Depending on what you are looking at (using the [`HitResult`][hitresult] in `Minecraft`), different things happen:
    - If you are holding an item with some `DataComponents#PIERCING_WEAPON`:
        - Server-only: `PiercingWeapon#attack` is called.
            - `ProjectileUtil#getHitEntitiesAlong` is used to get all entities that are:
                - Within the attackers's interaction range and the item's attack range (`DataComponents#ATTACK_RANGE`).
                - Not invulnerable and can be hit by a projectile.
                - Not passengers of the same vehicle.
            - `LivingEntity#stabAttack` is called for each entity, returning true if the target is successfully damaged, knockbacked, or dismounted:
                - The damage source is obtained from `ItemStack#getDamageSource`.
                - [`Entity#hurtServer`][hurt] is called.
                - If the piercing weapon deals knockback, then `LivingEntity#causeExtraKnockback` is called.
                - If the piercing weapon dismounts on hit and the target is a passenger, then `Entity#stopRiding` is called.
                - If the target is a `LivingEntity`, then `ItemStack#hurtEnemy` is called.
                - If the target is successfully damaged, then `EnchantmentHelper#doPostAttackEffects` is applied.
                - If the target is successfully damaged, knockbacked, or dismounted, then:  
                    - `LivingEntity#setLastHurtMob` is called with the target entity.
                    - `LivingEntity#playAttackSound` is called.
        - `LivingEntity#onAttack` is called.
        - `LivingEntity#lungerForwardMaybe` is called. This applies the lunge effects via `EnchantmentHelper#doLungeEffects`.
        - Server-only: If `LivingEntity#stabAttack` returned true for at least one target, `PiercingWeapon#makeHitSound` is called.
        - Server-only: `PiercingWeapon#makeSound` is called.
        - `LivingEntity#swing` is called.
    - If you are looking at an [entity] that is within your reach:
        - `AttackEntityEvent` is fired. If the event is canceled, the pipeline ends.
        - `IItemExtension#onLeftClickEntity` is called. If it returns true, the pipeline ends.
        - `Entity#isAttackable` is called on the target. If it returns false, the pipeline ends.
        - `Entity#skipAttackInteraction` is called on the target. If it returns true, the pipeline ends.
        - If the target is in the `minecraft:redirectable_projectile` tag (by default this is fireballs and wind charges) and an instance of `Projectile`, the target is deflected and the pipeline ends.
        - Entity base damage (the value of the `minecraft:attack_damage` [attribute]) and enchantment bonus damage are calculated as two separate floats. If both are 0, the pipeline ends.
            - Note that this excludes [attribute modifiers][attributemodifier] from the main hand item, these are added after the check.
        - `minecraft:attack_damage` attribute modifiers from the main hand item are added to the base damage.
        - `CriticalHitEvent` is fired. If the event's `#isCriticalHit` method returns true, the base damage is multiplied with the value returned from the event's `#getDamageMultiplier` method, which defaults to 1.5 if [a number of conditions][critical] pass and 1.0 otherwise, but may be modified by the event.
        - Enchantment bonus damage is added to the base damage, resulting in the final damage value.
        - `SweepAttackEvent` is fired. If the event's `isSweeping` method returns true, then the player will perform a sweep attack. By default, this checks if the attack cooldown is > 90%, the attack is not a critical hit, the player is on the ground and not moving faster than their `minecraft:movement_speed` attribute value.
        - [`Entity#hurtOrSimulate`][hurt] is called. If it returns false, the pipeline ends.
        - If the target is an instance of `LivingEntity` and the attack strength is greater than 90%, the player is sprinting, and the `minecraft:attack_knockback` attribute value after being modified by enchantments is greater than 0, `LivingEntity#knockback` is called.
            - Within that method, `LivingKnockBackEvent` is fired. If the event is canceled, then no knockback is applied.
        - The player performs a sweep attack on nearby `LivingEntity`s based on `SweepAttackEvent#isSweeping`.
            - Within that method, `LivingEntity#knockback` is called again if the entity is in reach of the player and `Entity#hurtServer` returns true, which in turn fires `LivingKnockBackEvent` another time.
        - `Item#hurtEnemy` is called. This can be used for post-attack effects. For example, the mace launches the player back in the air here, if applicable.
        - `Item#postHurtEnemy` is called. Durability damage is applied here.
            - If the durability hits zero, turning the stack into an `ItemStack#EMPTY`, `PlayerDestroyItemEvent` is fired.
    - If you are looking at a [block] that is within your reach:
        - The [block breaking sub-pipeline][blockbreak] is initiated.
    - Otherwise:
        - `PlayerInteractEvent.LeftClickEmpty` is fired.

## Right-Clicking an Item

During the right-clicking pipeline, a number of methods returning one of two result types (see below) are called. Most of these methods cancel the pipeline if an explicit success or an explicit failure is returned. For the sake of readability, this "explicit success or explicit failure" will be called a "definitive result" from now on.

- `InputEvent.InteractionKeyMappingTriggered` is fired with the right mouse button and the main hand. If the [event][event] is [canceled][cancel], the pipeline ends.
- Several circumstances are checked, for example that you are not in spectator mode or that all required [feature flags][featureflag] for the [`ItemStack`][itemstack] in your main hand are enabled. If at least one of these checks fails, the pipeline ends.
- Depending on what you are looking at (using the [`HitResult`][hitresult] in `Minecraft`), different things happen:
    - If you are looking at an [entity] that is within your reach and not outside the world border:
        - `PlayerInteractEvent.EntityInteractSpecific` is fired. If the event is canceled, the pipeline ends.
        - `Entity#interactAt` will be called **on the entity you are looking at**. If it returns a definitive result, the pipeline ends.
            - If you want to add behavior for your own entity, override this method. If you want to add behavior for a vanilla entity, use the event.
        - If the entity opens an interface (for example a villager trading GUI or a chest minecart GUI), the pipeline ends.
        - `PlayerInteractEvent.EntityInteract` is fired. If the event is canceled, the pipeline ends.
        - `Entity#interact` is called **on the entity you are looking at**. If it returns a definitive result, the pipeline ends.
            - If you want to add behavior for your own entity, override this method. If you want to add behavior for a vanilla entity, use the event.
            - For [`Mob`s][livingentity], the override of `Entity#interact` handles things like leashing and spawning babies when the `ItemStack` in your main hand is a spawn egg, and then defers mob-specific handling to `Mob#mobInteract`. The rules for results for `Entity#interact` apply here as well.
        - If the entity you are looking at is a `LivingEntity`, `Item#interactLivingEntity` is called on the `ItemStack` in your main hand. If it returns a definitive result, the pipeline ends.
    - If you are looking at a [block] that is within your reach and not outside the world border:
        - `PlayerInteractEvent.RightClickBlock` is fired. If the event is canceled, the pipeline ends. You may also specifically deny only block or item usage in this event.
        - `IItemExtension#onItemUseFirst` is called. If it returns a definitive result, the pipeline ends.
        - If `IItemExtension#doesSneakBypassUse` returns false and the event does not deny block usage, `UseItemOnBlockEvent` is fired. If the event is canceled, the cancellation result is used. Otherwise, `BlockBehaviour#useItemOn` is called. If it returns a definitive result, the pipeline ends.
        - If the `InteractionResult` is an instance of `TryEmptyHandInteraction` (e.g., `TRY_WITH_EMPTY_HAND`) and the executing hand is the main hand, then `BlockBehaviour#useWithoutItem` is called. If it returns a definitive result, the pipeline ends.
        - If the event does not deny item usage, `Item#useOn` is called. If it returns a definitive result, the pipeline ends.
     - Otherwise:
        - `PlayerInteractEvent.RightClickEmpty` is fired.
- `PlayerInteractEvent.RightClickItem` is fired. If the event is canceled, the pipeline ends.
- `Item#use` is called.
    - If the `InteractionResult` is an instance of  `Success` (e.g., `SUCCESS`), then the `ItemStack` is changed to `Success#heldItemTransformedTo`.
- If the current stack does not match the original stack and the new stack is empty, then `PlayerDestroyItemEvent` is fired.
- The above process runs a second time, this time with the off hand instead of the main hand.

### `InteractionResult`

`InteractionResult` is a sealed interface that represents the result of some interaction between an item or an empty hand and some object (e.g. entities, blocks, etc.). The interface is broken into four records, where there are six potential default states.

First there is `InteractionResult.Success`, which indicates that the operation should be considered successful, ending the pipeline. A successful state has two parameters: the `SwingSource`, which indicates whether the entity should swing on the respective [logical side][side]; and the `InteractionResult.ItemContext`, which holds whether the interaction was caused by a held item, and what the held item transformed into after use. The swing source is determined by one of the default states: `InteractionResult#SUCCESS` for client swing, `InteractionResult#SUCCESS_SERVER` for server swing, and `InteractionResult#CONSUME` for no swing. The item context is set via `Success#heldItemTransformedTo` if the `ItemStack` changed, or `withoutItem` if there wasn't an interaction between the held item and the object. The default sets there was an item interaction but no transformation.

```java
// In some method that returns an interaction result

// Item in hand will turn into an apple
return InteractionResult.SUCCESS.heldItemTransformedTo(new ItemStack(Items.APPLE));
```

:::note
`SUCCESS` and `SUCCESS_SERVER` should generally never be used in the same method. If the client has enough information to determine when to swing, then `SUCCESS` should always be used. Otherwise, if it relies on server information not present on the client, `SUCCESS_SERVER` should be used.
:::

Then there is `InteractionResult.Fail`, implemented by `InteractionResult#FAIL`, which indicates that the operation should be considered failed, allowing no further interaction to occur. The pipeline will end. This can be used anywhere, but it should be used with care outside of `Item#useOn` and `Item#use`. In many cases, using `InteractionResult#PASS` makes more sense.

Finally, there is `InteractionResult.Pass` and `InteractionResult.TryWithEmptyHandInteraction`, implemented by `InteractionResult#PASS` and `InteractionResult#TRY_WITH_EMPTY_HAND` respectively. These records indicate when an operation should be considered neither successful or failed, and the pipeline should continue. `PASS` is the default behavior for all `InteractionResult` methods except `BlockBehaviour#useItemOn`, which returns `TRY_WITH_EMPTY_HAND`. More specifically, if `BlockBehaviour#useItemOn` returns anything but `TRY_WITH_EMPTY_HAND`, `BlockBehaviour#useWithoutItem` will not be called regardless of if the item is in the main hand.

Some methods have special behavior or requirements, which are explained in the below chapters.

#### `Item#useOn`

If you want the operation to be considered successful, but you do not want the arm to swing or an `ITEM_USED` stat point to be awarded, use `InteractionResult#CONSUME` and calling `#withoutItem`.

```java
// In Item#useOn
return InteractionResult.CONSUME.withoutItem();
```

#### `Item#use`

This is the only instance where the transformed `ItemStack` is used from a `Success` variant (`SUCCESS`, `SUCCESS_SERVER`, `CONSUME`). The resulting `ItemStack` set by `Success#heldItemTransformedTo` replaces the `ItemStack` the usage was initiated with, if it has changed.

The default implementation of `Item#use` returns `InteractionResult#CONSUME` when the item is edible (has `DataComponents#CONSUMABLE`) and the player can eat the item (because they are hungry, or because the item is always edible) and `InteractionResult#FAIL` when the item is edible (has `DataComponents#CONSUMABLE`) but the player cannot eat the item. If the item is equippable (has `DataComponents#EQUIPPABLE`), then it returns `InteractionResult#SUCCESS` on swap with the held item replaced by the swapped item (via `heldItemTransformedTo`), or `InteractionResult#FAIL` if the enchantment on the armor has the `EnchantmentEffectComponents#PREVENT_ARMOR_CHANGE` component. If the item can block attacks (has `DataComponents#BLOCKS_ATTACKS`), then `Item#startUsingItem` is called before returning `InteractionResult#CONSUME`. Otherwise `InteractionResult#PASS` is returned.

Returning `InteractionResult#FAIL` here while considering the main hand will prevent offhand behavior from running. If you want offhand behavior to run (which you usually want), return `InteractionResult#PASS` instead.

## Middle-Clicking

- If the [`HitResult`][hitresult] in `Minecraft.getInstance().hitResult` is null or of type `MISS`, the pipeline ends.
- `InputEvent.InteractionKeyMappingTriggered` is fired with the left mouse button and the main hand. If the [event][event] is [canceled][cancel], the pipeline ends.
- Depending on what you are looking at (using the `HitResult` in `Minecraft.getInstance().hitResult`), different things happen:
    - If you are looking at an [entity] that is within your reach:
        - If `Entity#isPickable` returns false, the pipeline ends.
        - If `Player#isWithinEntityInteractionRange` returns false, the pipeline ends.
        - `Entity#getPickResult` is called. A hotbar slot that matches the resulting `ItemStack` is set active, if such a hotbar slot exists. Otherwise, if the player is in creative, the resulting `ItemStack` is added to the player's inventory.
            - By default, this method forwards to `Entity#getPickResult`, which can be overridden by modders.
    - If you are looking at a [block] that is within your reach:
        - If `Player#isWithinBlockInteractionRange` returns false, the pipeline ends.
        - `IBlockExtension#getCloneItemStack` is called (which by default delegates to `BlockBehaviour#getCloneItemStack`) and becomes the "selected" `ItemStack`.
            - By default, this returns the `Item` representation of the `Block`.
        - If the Control key is held down, the player is in creative and the targeted block has a [`BlockEntity`][blockentity]:
            - The `BlockEntity`'s data is obtained from `BlockEntity#saveCustomOnly`
                - `BlockEntity#removeComponentsFromTag` is called as a post processing step.
            - The `BlockEntity`'s data is added to the "selected" `ItemStack` via `DataComponents#BLOCK_ENTITY_DATA`.
        - A hotbar slot that matches the "selected" `ItemStack` is set active, if such a hotbar slot exists. Otherwise, if the player is in creative, the "selected" `ItemStack` is added to the player's inventory.

[attribute]: ../entities/attributes.md
[attributemodifier]: ../entities/attributes.md#attribute-modifiers
[block]: ../blocks/index.md
[blockbreak]: ../blocks/index.md#breaking-a-block
[blockentity]: ../blockentities/index.md
[cancel]: ../concepts/events.md#cancellable-events
[critical]: https://minecraft.wiki/w/Damage#Critical_hit
[effect]: mobeffects.md
[entity]: ../entities/index.md
[event]: ../concepts/events.md
[featureflag]: ../advanced/featureflags.md
[hitresult]: #hitresults
[hurt]: ../entities/index.md#damaging-entities
[itemstack]: index.md#itemstacks
[itemuseon]: #itemuseon
[livingentity]: ../entities/livingentity.md
[physicalside]: ../concepts/sides.md#the-physical-side
[side]: ../concepts/sides.md#the-logical-side

## items/mobeffects

---
sidebar_position: 6
---
# Mob Effects & Potions

Status effects, sometimes known as potion effects and referred to in-code as `MobEffect`s, are effects that influence a [`LivingEntity`][livingentity] every tick. This article explains how to use them, what the difference between an effect and a potion is, and how to add your own.

## Terminology

- A `MobEffect` affects an entity every tick. Like [blocks][block] or [items][item], `MobEffect`s are registry objects, meaning they must be [registered][registration] and are singletons.
    - An **instant mob effect** is a special kind of mob effect that is designed to be applied for one tick. Vanilla has two instant effects, Instant Health and Instant Harming.
- A `MobEffectInstance` is an instance of a `MobEffect`, with a duration, amplifier and some other properties set (see below). `MobEffectInstance`s are to `MobEffect`s what [`ItemStack`s][itemstack] are to `Item`s.
- A `Potion` is a collection of `MobEffectInstance`s. Vanilla mainly uses potions for the four potion items (read on), however, they can be applied to any item at will. It is up to the item if and how the item then uses the potion set on it.
- A **potion item** is an item that is meant to have a potion set on it. This is an informal term, the vanilla `PotionItem` class has nothing to do with this (it refers to the "normal" potion item). Minecraft currently has four potion items: potions, splash potions, lingering potions, and tipped arrows; however more may be added by mods.

## `MobEffect`s

To create your own `MobEffect`, extend the `MobEffect` class:

```java
public class MyMobEffect extends MobEffect {
    public MyMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
    
    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        // Apply your effect logic here.

        // If this returns false when shouldApplyEffectTickThisTick returns true, the effect will immediately be removed
        return true;
    }
    
    // Whether the effect should apply this tick. Used e.g. by the Regeneration effect that only applies
    // once every x ticks, depending on the tick count and amplifier.
    @Override
    public boolean shouldApplyEffectTickThisTick(int tickCount, int amplifier) {
        return tickCount % 2 == 0; // replace this with whatever check you want
    }
    
    // Utility method that is called when the effect is first added to the entity.
    // This does not get called again until all instances of this effect have been removed from the entity.
    @Override
    public void onEffectAdded(LivingEntity entity, int amplifier) {
        super.onEffectAdded(entity, amplifier);
    }

    // Utility method that is called when the effect is added to the entity.
    // This gets called every time this effect is added to the entity.
    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
    }
}
```

Like all registry objects, `MobEffect`s must be [registered][registration], like so:

```java
// MOB_EFFECTS is a DeferredRegister<MobEffect>
public static final Holder<MobEffect> MY_MOB_EFFECT = MOB_EFFECTS.register("my_mob_effect", () -> new MyMobEffect(
        //Can be either BENEFICIAL, NEUTRAL or HARMFUL. Used to determine the potion tooltip color of this effect.
        MobEffectCategory.BENEFICIAL,
        //The color of the effect particles in RGB format.
        0xffffff
));
```

The `MobEffect` class also provides default functionality for adding [attribute modifiers][attributemodifier] to affected entities, and also removing them when the effect expires or is removed through other means. For example, the speed effect adds an attribute modifier for movement speed. Effect attribute modifiers are added like so:

```java
public static final Holder<MobEffect> MY_MOB_EFFECT = MOB_EFFECTS.register("my_mob_effect", () -> new MyMobEffect(...)
        .addAttributeModifier(Attributes.ATTACK_DAMAGE, Identifier.fromNamespaceAndPath("examplemod", "effect.strength"), 2.0, AttributeModifier.Operation.ADD_VALUE)
);
```

### `InstantenousMobEffect`

If you want to create an instant effect, you can use the helper class `InstantenousMobEffect` instead of the regular `MobEffect` class, like so:

```java
public class MyMobEffect extends InstantenousMobEffect {
    public MyMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public void applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        // Apply your effect logic here.
    }
}
```

Then, [register][registration] your effect like normal.

### Events

Many effects have their logic applied in other places. For example, the levitation effect is applied in the living entity movement handler. For modded `MobEffect`s, it often makes sense to apply them in an [event handler][events]. NeoForge also provides a few events related to effects:

- `MobEffectEvent.Applicable` is fired when the game checks whether a `MobEffectInstance` can be applied to an entity. This event can be used to deny or force adding the effect instance to the target.
- `MobEffectEvent.Added` is fired when the `MobEffectInstance` is added to the target. This event contains information about a previous `MobEffectInstance` that may have been present on the target.
- `MobEffectEvent.Expired` is fired when the `MobEffectInstance` expires, i.e. the timer goes to zero.
- `MobEffectEvent.Remove` is fired when the effect is removed from the entity through means other than expiring, e.g. through drinking milk or via commands.

## `MobEffectInstance`s

A `MobEffectInstance` is, simply put, an effect applied to an entity. Creating a `MobEffectInstance` is done by calling the constructor:

```java
MobEffectInstance instance = new MobEffectInstance(
        // The mob effect to use.
        MobEffects.REGENERATION,
        // The duration to use, in ticks. Defaults to 0 if not specified.
        500,
        // The amplifier to use. This is the "strength" of the effect, i.e. Strength I, Strength II, etc.
        // Must be between 0 and 255 (inclusive). Defaults to 0 if not specified.
        0,
        // Whether the effect is an "ambient" effect, meaning it is being applied by an ambient source,
        // of which Minecraft currently has the beacon and the conduit. Defaults to false if not specified.
        false,
        // Whether the effect is visible in the inventory. Defaults to true if not specified.
        true,
        // Whether an effect icon is visible in the top right corner. Defaults to true if not specified.
        true
);
```

Several constructor overloads are available, omitting the last 1-5 parameters, respectively.

:::info
`MobEffectInstance`s are mutable. If you need a copy, call `new MobEffectInstance(oldInstance)`.
:::

### Using `MobEffectInstance`s

A `MobEffectInstance` can be added to a `LivingEntity` like so:

```java
MobEffectInstance instance = new MobEffectInstance(...);
livingEntity.addEffect(instance);
```

Similarly, `MobEffectInstance`s can also be removed from an `LivingEntity`. Since a `MobEffectInstance` overwrites pre-existing `MobEffectInstance`s of the same `MobEffect` on the entity, there can only ever be one `MobEffectInstance` per `MobEffect` and entity. As such, specifying the `MobEffect` suffices when removing:

```java
livingEntity.removeEffect(MobEffects.REGENERATION);
```

:::info
`MobEffect`s can only be applied to `LivingEntity` or its subclasses, i.e. players and mobs. Things like items or thrown snowballs cannot be affected by `MobEffect`s.
:::

## `Potion`s

`Potion`s are created by calling the constructor of `Potion` with the `MobEffectInstance`s you want the potion to have. For example:

```java
//POTIONS is a DeferredRegister<Potion>
public static final Holder<Potion> MY_POTION = POTIONS.register("my_potion", registryName -> new Potion(
    // The suffix applied to the potion
    registryName.getPath(),
    // The effects used by the potion
    new MobEffectInstance(MY_MOB_EFFECT, 3600)
));
```

The name of the potion is the first constructor argument. It is used as the suffix for a translation key; for example, the long and strong potion variants in vanilla use this to have the same names as their base variant.

The `MobEffectInstance` parameter of `new Potion` is a vararg. This means that you can add as many effects as you want to the potion. This also means that it is possible to create empty potions, i.e. potions that don't have any effects. Simply call `new Potion()` and you're done! (This is how vanilla adds the `awkward` potion, by the way.)

The `PotionContents` class offers various helper methods related to potion items. Potion item store their `PotionContents` via `DataComponent#POTION_CONTENTS`.

### Brewing

Now that your potion is added, potion items are available for your potion. However, there is no way to obtain your potion in survival, so let's change that!

Potions are traditionally made in the Brewing Stand. Unfortunately, Mojang does not provide [datapack][datapack] support for brewing recipes, so we have to be a little old-fashioned and add our recipes through code via the `RegisterBrewingRecipesEvent` event. This is done like so:

```java
@SubscribeEvent // on the game event bus
public static void registerBrewingRecipes(RegisterBrewingRecipesEvent event) {
    // Gets the builder to add recipes to
    PotionBrewing.Builder builder = event.getBuilder();

    // Will add brewing recipes for all container potions (e.g. potion, splash potion, lingering potion)
    builder.addMix(
        // The initial potion to apply to
        Potions.AWKWARD,
        // The brewing ingredient. This is the item at the top of the brewing stand.
        Items.FEATHER,
        // The resulting potion
        MY_POTION
    );
}
```

[attributemodifier]: ../entities/attributes.md#attribute-modifiers
[block]: ../blocks/index.md
[commonsetup]: ../concepts/events.md#event-buses
[datapack]: ../resources/index.md#data
[events]: ../concepts/events.md
[item]: index.md
[itemstack]: index.md#itemstacks
[livingentity]: ../entities/livingentity.md
[registration]: ../concepts/registries.md#methods-for-registering
[uuidgen]: https://www.uuidgenerator.net/version4

## items/tools

---
sidebar_position: 4
---
# Tools

Tools are [items][item] whose primary use is to break [blocks][block]. Many mods add new tool sets (for example copper tools) or new tool types (for example hammers).

## Custom Tool Sets

A tool set typically consists of six items: a pickaxe, an axe, a shovel, a hoe, a sword, and a spear (swords and spears aren't tools in the classical sense, but are included here for consistency as well). All of these tools are implemented using a combination of the following fourteen [data components][datacomponents]:

- `DataComponents#MAX_DAMAGE` and `#DAMAGE` for durability
- `#MAX_STACK_SIZE` to set the stack size to `1`
- `#REPAIRABLE` for repairing a tool in an anvil
- `#ENCHANTABLE` for the maximum [enchanting][enchantment] value
- `#ATTRIBUTE_MODIFIERS` for attack damage and attack speed
- `#TOOL` for mining information
- `#WEAPON` for damage taken by the item and shield disabling
- `#ATTACK_RANGE` for attack range while swinging the weapon
- `#DAMAGE_TYPE` for the damage type to deal
- `#MINIMUM_ATTACK_CHARGE` for the minimum amount of ticks required before an attack can be made with this weapon
- `#SWING_ANIMATION` for the animation to play when swinging the weapon
- `#PIERCING_WEAPON` for a stab attack with multiple entities
- `#KINETIC_WEAPON` for a item-use attack with multiple entities based on momentum
- `#USE_EFFECTS` for applying some effects to the entity when using the item

Commonly, each tool is setup using `Item.Properties#tool`, `#sword`, `#spear`, or one of tool's delegates (`pickaxe`, `axe`, `hoe`, `shovel`). These are typically handled by passing in the utility record `ToolMaterial`. Note that other items usually considered tools, such as shears, do not have their common mining logic implemented through data components. Instead, they directly extend `Item` and handle the mining by overriding the relevant methods. Interact behavior (right-click by default) also does not have a data component, meaning that shovels, axes, and hoes have their own tool classes `ShovelItem`, `AxeItem`, and `HoeItem` respectively.

To create a standard set of tools, you must first define a `ToolMaterial`. Reference values can be found within the constants in `ToolMaterial`. This example uses copper tools, you can use your own material here and adjust the values as needed.

```java
// We place copper somewhere between stone and iron.
public static final ToolMaterial COPPER_MATERIAL = new ToolMaterial(
        // The tag that determines what blocks this material cannot break. See below for more information.
        MyBlockTags.INCORRECT_FOR_COPPER_TOOL,
        // Determines the durability of the material.
        // Stone is 131, iron is 250.
        200,
        // Determines the mining speed of the material. Unused by swords.
        // Stone uses 4, iron uses 6.
        5f,
        // Determines the attack damage bonus. Different tools use this differently. For example, swords do (getAttackDamageBonus() + 4) damage.
        // Stone uses 1, iron uses 2, corresponding to 5 and 6 attack damage for swords, respectively; our sword does 5.5 damage now.
        1.5f,
        // Determines the enchantability of the material. This represents how good the enchantments on this tool will be.
        // Gold uses 22, we put copper slightly below that.
        20,
        // The tag that determines what items can repair this material.
        Tags.Items.INGOTS_COPPER
);
```

Now that we have our `ToolMaterial`, we can use it for [registering] tools. All `tool` delegates have the same three parameters:

```java
// ITEMS is a DeferredRegister.Items
public static final DeferredItem<Item> COPPER_SWORD = ITEMS.registerItem(
    "copper_sword",
    props -> new Item(
        // The item properties.
        props.sword(
            // The material to use.
            COPPER_MATERIAL,
            // The type-specific attack damage bonus. 3 for swords, 1.5 for shovels, 1 for pickaxes, varying for axes and hoes.
            3,
            // The type-specific attack speed modifier. The player has a default attack speed of 4, so to get to the desired
            // value of 1.6f, we use -2.4f. -2.4f for swords, -3f for shovels, -2.8f for pickaxes, varying for axes and hoes.
            -2.4f,
        )
    )
);

public static final DeferredItem<Item> COPPER_AXE = ITEMS.registerItem("copper_axe", props -> new Item(props.axe(...)));
public static final DeferredItem<Item> COPPER_PICKAXE = ITEMS.registerItem("copper_pickaxe", props -> new Item(props.pickaxe(...)));
public static final DeferredItem<Item> COPPER_SHOVEL = ITEMS.registerItem("copper_shovel", props -> new Item(props.shovel(...)));
public static final DeferredItem<Item> COPPER_HOE = ITEMS.registerItem("copper_hoe", props -> new Item(props.hoe(...)));

public static final DeferredItem<Item> COPPER_SPEAR = ITEMS.registerItem(
    "copper_spear",
    props -> new Item(
        props.spear(
            // The material to use.
            COPPER_MATERIAL,
            // The type-specific attack speed modifier. This value is scaled by performing the reciprocal of this value, then
            // subtracting 4.
            0.85f,
            // The damage multiplier applied when using the spear as a kinetic weapon, assuming one of the conditions are met.
            0.82f,
            // The number of seconds that must pass before the spear can be used as a kinetic weapon.
            0.65f,
            // The maximum number of seconds that can pass while using the kinetic weapon to dismount a hit entity.
            4.0f,
            // The minimum speed, in blocks, of the attacker using the kinetic weapon to dismount a hit entity.
            9.0f,
            // The maximum number of seconds that can pass while using the kinetic weapon to knockback a hit entity.
            8.25f,
            // The minimum speed, in blocks, of the attacker using the kinetic weapon to knockack a hit entity.
            5.1f,
            // The maximum number of seconds that can pass while using the kinetic weapon to damage a hit entity.
            12.5f,
            // The minimum speed, in blocks, of the attacker using the kinetic weapon to damage a hit entity. This is relative
            // to the attacked entity's speed.
            4.6f
        )
    )
);
```

:::note
`tool` takes in two additional parameters: the `TagKey` representing what blocks can be mined, and the number of seconds that blockers (e.g., shields) are disabled for when hit.
:::

### Tags

When creating a `ToolMaterial`, it is assigned a block [tag][tags] containing blocks that will not drop anything if broken with this tool. For example, the `minecraft:incorrect_for_stone_tool` tag contains blocks like Diamond Ore, and the `minecraft:incorrect_for_iron_tool` tag contains blocks like Obsidian and Ancient Debris. To make it easier to assign blocks to their incorrect mining levels, a tag also exists for blocks that need this tool to be mined. For example, the `minecraft:needs_iron_tool` tag contains blocks like Diamond Ore, and the `minecraft:needs_diamond_tool` tag contains blocks like Obsidian and Ancient Debris.

You can reuse one of the incorrect tags for your tool if you're fine with that. For example, if we wanted our copper tools to just be more durable stone tools, we'd pass in `BlockTags#INCORRECT_FOR_STONE_TOOL`.

Alternatively, we can create our own tag, like so:

```java
// This tag will allow us to add these blocks to the incorrect tags that cannot mine them
public static final TagKey<Block> NEEDS_COPPER_TOOL = TagKey.create(BuiltInRegistries.BLOCK.key(), Identifier.fromNamespaceAndPath(MOD_ID, "needs_copper_tool"));

// This tag will be passed into our material
public static final TagKey<Block> INCORRECT_FOR_COPPER_TOOL = TagKey.create(BuiltInRegistries.BLOCK.key(), Identifier.fromNamespaceAndPath(MOD_ID, "incorrect_for_cooper_tool"));
```

And then, we populate our tag. For example, let's make copper able to mine gold ores, gold blocks and redstone ore, but not diamonds or emeralds. (Redstone blocks are already mineable by stone tools.) The tag file is located at `src/main/resources/data/mod_id/tags/block/needs_copper_tool.json` (where `mod_id` is your mod id):

```json5
{
    "values": [
        "minecraft:gold_block",
        "minecraft:raw_gold_block",
        "minecraft:gold_ore",
        "minecraft:deepslate_gold_ore",
        "minecraft:redstone_ore",
        "minecraft:deepslate_redstone_ore"
    ]
}
```

Then, for our tag to pass into the material, we can provide a negative constraint for any tools that are incorrect for stone tools but within our copper tools tag. The tag file is located at `src/main/resources/data/mod_id/tags/block/incorrect_for_cooper_tool.json`:

```json5
{
    "values": [
        "#minecraft:incorrect_for_stone_tool"
    ],
    "remove": [
        "#mod_id:needs_copper_tool"
    ]
}
```

Finally, we can pass our tag into our material instance, as seen above.

If you want to check if a tool can make a block state drop its blocks, call `Tool#isCorrectForDrops`. The `Tool` can be obtained by calling `ItemStack#get` with `DataComponents#TOOL`.

## Custom Tools

Custom tools can be created by adding a `Tool` [data component][datacomponents] (via `DataComponents#TOOL`) to the list of default components on your item via `Item.Properties#component`.

A `Tool` contains a list of `Tool.Rule`s, the default mining speed when holding the tool (`1` by default), and the amount of damage the tool should take when mining a block (`1` by default). A `Tool.Rule` contains three pieces of information: a `HolderSet` of blocks to apply the rule to, an optional speed at which to mine the blocks in the set, and an optional boolean at which to determine whether these blocks can drop from this tool. If the optional are not set, then the other rules will be checked. The default behavior if all rules fail is the default mining speed and that the block cannot be dropped.

:::note
A `HolderSet` can be created from a `TagKey` via `Registry#getOrThrow`.
:::

Creating any tool or multitool-like item (i.e. an item that combines two or more tools into one, e.g. an axe and a pickaxe as one item) is possible without using any of the existing `ToolMaterial` references. It can be implemented using a combination of the following parts:

- Adding a `Tool` with your own rules by setting `DataComponents#TOOL` via `Item.Properties#component`.
- Adding [attribute modifiers][attributemodifier] to the item (e.g. attack damage, attack speed) via `Item.Properties#attributes`.
- Adding item durability via `Item.Properties#durability`.
- Allowing the item to be repaired via `Item.Properties#repariable`.
- Allowing the item to be enchanted via `Item.Properties#enchantable`.
- Allowing the item to be used as a weapon and potentially disable blockers by setting `DataComponents#WEAPON` via `Item.Properties#component`.
- Overriding `IItemExtension#canPerformAction` to determine what [`ItemAbility`s][itemability] the item can perform.
- Calling `IBlockExtension#getToolModifiedState` if you want your item to modify the block state on right click based on the `ItemAbility`s.
- Adding your tool to some of the `minecraft:enchantable/*` `ItemTags` so that your item can have certain enchantments applied to it.
- Adding your tool to some of the `minecraft:*_preferred_weapons` tags to allow mobs to favor your weapon to pickup and use.

For shields, you can apply the [`DataComponents#EQUIPPABLE`][equippable] data component for the offhand and `DataComponents#BLOCKS_ATTACKS` for reducing damage to the held entity when active.

## `ItemAbility`s

`ItemAbility`s are an abstraction over what an item can and cannot do. This includes both left-click and right-click behavior. NeoForge provides default `ItemAbility`s in the `ItemAbilities` class:

- Axe right-click abilities for stripping (logs), scraping (oxidized copper), and unwaxing (waxed copper).
- Shovel right-click abilities for flattening (dirt paths) and dousing (campfires).
- Shear abilities for digging (breaking blocks), harvesting (honeycombs), removing armor (armored wolves), carving (pumpkins), disarming (tripwires), and trimming (stop plants from growing).
- Abilities for sword sweeping, hoe tilling, fishing rod casting, trident throwing, brush brushing, firestarter lighting, and spyglass scoping.

To create your own `ItemAbility`s, use `ItemAbility#get` - it will create a new `ItemAbility` if needed. Then, in a custom tool type, override `IItemExtension#canPerformAction` as needed.

To query if an `ItemStack` can perform a certain `ItemAbility`, call `IItemStackExtension#canPerformAction`. Note that this works on any `Item`, not just tools.

[block]: ../blocks/index.md
[datacomponents]: datacomponents.md
[enchantment]: ../resources/server/enchantments/index.md#enchantment-costs-and-levels
[equippable]: armor.md#equippable
[item]: index.md
[itemability]: #itemabilitys
[registering]: ../concepts/registries.md#methods-for-registering
[tags]: ../resources/server/tags.md