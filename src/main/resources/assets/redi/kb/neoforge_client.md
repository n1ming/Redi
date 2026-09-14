# NeoForge 客户端与渲染

> 来源:neoforged/Documentation 官方文档(英文原文,API 名与代码签名原样保留)。
> 回答时用中文解释,类名/方法名保持英文。

## rendering/feature

---
sidebar_position: 1
---
# Features

A rendering feature defines a set of objects not baked into the level geometry, such as entities, text, and particles. These objects typically have a dynamic position, so things like falling or held blocks and items also fall into this category. The purpose of the feature renderer is then to better batch and order the objects being rendered to the screen. The feature renderer is broken into two phases: the submission phase, where all features are collected; and the rendering phases, where the collected features are rendered.

## Submitting Features

Feature submission is typically handled by the underlying subsystem responsible for those objects: [`EntityRenderer` for entities][entities], [`BlockEntityRenderer` for block entities][blockentities], [`ParticleGroupRenderState` for particles][particles], etc. Each provides their own `submit` method, usually taking in some general render state of the object. The necessary elements are then submitted through the `SubmitNodeCollector` and stored in a `SubmitNodeCollection` tree map for rendering.

The following methods are made available through the collector, in the order they are ultimately rendered:

| Method                     | Description                                                                                                |
|:--------------------------:|:-----------------------------------------------------------------------------------------------------------|
| `submitShadow`             | A number of black ovals for the desired radius, location, and opacity.                                     |
| `submitNameTag`            | Text, transparency sorted.                                                                                 |
| `submitText`               | Text.                                                                                                      |
| `submitFlame`              | The flame overlay applied to entities.                                                                     |
| `submitLeash`              | A 24-segment plane.                                                                                        |
| `submitModel`              | `Model`s with render states, transparency sorted.                                                          |
| `submitModelPart`          | `ModelPart`s.                                                                                              |
| `submitMovingBlock`        | A list of `BlockStateModelPart`s with dynamic lighting.                                                    |
| `submitBlockModel`         | A `BlockStateModel` with baked lighting.                                                                   |
| `submitBreakingBlockModel` | The crumbling overlay on top of a `BlockStateModel`.                                                       |
| `submitItem`               | A deconstructed `ItemStackRenderState`.                                                                    |
| `submitCustomGeometry`     | An arbitrary method that defines the vertices uploaded to the buffer of the given `RenderType`.            |
| `submitParticleGroup`      | A renderer for caching and writing a batch of particle quads.                                              |

NeoForge also adds `submitMultiLayerBlockModel` for submitting a list of `BlockStateModelPart`s with full support for per-quad render types, rather than pushing all quads into a single type layer.

:::warning
Each of the elements submitted to the collector should be considered immutable after the method is invoked. Some elements like the `PoseStack` are snapshotted at that time to prevent any further mutations.
:::

Technically, all of the methods listed above are part of the superinterface `OrderedSubmitNodeCollector`. This is because the collector can group the features into 'orders', which represent a single pass of the renderer. By default, all features are rendered on order 0, meaning they will be drawn based on the rendering order defined below. Orders with a smaller number will be rendered first while orders with a large number will be rendered after. `SubmitNodeCollector#order` can be used to specify the order of which the element is drawn:

```java
// Assume we have some SubmitNodeCollector collector

// This will be rendered on order 0.
collector.submitModel(...);

// This will be rendered before the model.
collector.order(-1).submitBlockModel(...);

// This will be rendered after the model.
collector.order(1).submitParticleGroup(...);
```

## Rendering Features

Feature rendering is handled through the `FeatureRenderDispatcher` via `renderAllFeatures`, which renders the submitted objects within the `SubmitNodeStorage` holding the `SubmitNodeCollection` tree map. The dispatcher contains a list of renderer classes which are responsible for rendering a given type of submitted objects. This order is split into two phases: rendering solid features, and rendering transparent geometry.

For solid features, within a given 'order', the features are rendered in the following order:

- Models
- Model parts
- Entity flame overlays
- Leashes
- Items
- Moving blocks
- Block Models
- NeoForge's multi-layer block models
- Custom geometry
- Particles

For transparent features, within a given 'order', the features are rendered in the following order:

- Shadows
- Models
- Model parts
- Name tags
- Text
- Items
- Moving blocks
- Block Models
- NeoForge's multi-layer block models
- Custom geometry

Transparent particles are rendered as a separate pass after all transparent features have rendered.

Once the features have finished rendering, the `SubmitNodeStorage` is cleared for its next use. The feature renderer may be called multiple times per frame, as not only is it used for the level, but also for the held item and [picture-in-picture gui renderer][gui]. Note that in those cases, `renderAllFeatures` is followed by `MultiBufferSource.BufferSource#endBatch` to the build the mesh and draw it to the buffer.

[blockentities]: ../blockentities/ber.md#blockentityrenderer
[entities]: ../entities/renderer.md#entity-renderers
[gui]: screens.md#picture-in-picture
[particles]: #TODO

## rendering/particles

# Client Particles

Particles are visual effects that polish the game and add immersion. Being mostly visual in nature, critical parts exist only on the physical (and logical) client [side].

This article covers the rendering-specific aspects of the particle. For more information on particles types, which are typically used to spawn particles; and particle descriptions, which can specify a particle's sprites, see the companion [particle types][particletype] article. 

## The `Particle` class

A `Particle` defines the client representation of what is spawned in the world and displayed to the player. Most properties and basic physics are controlled by fields such as `gravity`, `lifetime`, `hasPhysics`, `friction`, etc. The only two methods that are commonly overridden are `tick` and `move`, both of which do exactly as their name implies. As such, most custom particles are often short, consisting only a of a constructor that sets the desired fields with the occasional override in the two methods.

The two most common methods for constructing a particle are through subclassing `SingleQuadParticle` for one of its implementations (e.g. `SimpleAnimatedParticle`), which which blits a look-facing texture to the screen; or directly subclassing `Particle`, which gives full control of the [features] being submitted for rendering.

## A Single Quad

Particles that extend `SingleQuadParticle` draw a single quad with some atlas sprite to the screen. There are many helpers provided in the class, from setting the size of the particle (via the `quadSize` field or `scale` method), to tinting the texture (via `setColor` and `setAlpha`). However, the two most important things about a quad particle is the `TextureAtlasSprite` used as the texture, and where that sprite is obtained and rendered through `SingleQuadParticle.Layer`.

First, the `TextureAtlasSprite` is passed into the constructor, either as itself or more likely a `SpriteSet`, representing the texture over its lifetime. Initially, the sprite is set to the protected `sprite` field, but it can be updated during `tick` by calling `setSprite` or `setSpriteFromAge`, respectively.

:::tip
If the `age` or `lifetime` field is updated in the particle constructor, `setSpriteFromAge` should be called to display the appropriate texture.
:::

Then, during the [feature submission process][features], the `SingleQuadParticle.Layer` determines what atlas to use along with the pipeline used to draw the quad to the screen. Vanilla provides six layers by default:

| Layer                | Texture Atlas | For                                                    |
|:--------------------:|:-------------:|:-------------------------------------------------------|
| `OPAQUE_TERRAIN`     | Blocks        | Particles that use block textures with no transparency |
| `TRANSLUCENT_TERRAIN`| Blocks        | Particles that use block textures with transparency    |
| `OPAQUE_ITEMS`       | Items         | Particles that use item textures with no transparency  |
| `TRANSLUCENT_ITEMS`  | Items         | Particles that use item textures with transparency     |
| `OPAQUE`             | Particles     | Particles with no transparency                         |
| `TRANSLUCENT`        | Particles     | Particles with transparency                            |

For ease of convenience, if using one of the vanilla layers, you can call `SingleQuadParticle.Layer#bySprite` and pass in the texture to determine what layer your particle should be in.

Custom layers can be easily created by calling the constructor.

```java
public class MyQuadParticle extends SingleQuadParticle {

    public static final SingleQuadParticle.Layer EXAMPLE_LAYER = new SingleQuadParticle.Layer(
        // Whether the particle will have textures that are not fully opaque.
        true,
        // The texture atlas used to get the sprite from.
        // This should match `TextureAtlasSprite#atlasLocation`.
        TextureAtlas.LOCATION_PARTICLES,
        // The render pipeline used to draw the particle.
        // Custom render pipelines should be based from `RenderPipelines#PARTICLE_SNIPPET`
        // to specify the available uniforms and samplers.
        RenderPipelines.WEATHER_DEPTH_WRITE
    );

    private final SpriteSet spriteSet;

    // First four parameters are self-explanatory.
    // The sprite set or atlas sprite are typically given through the provider, see below.
    // Additional parameters can be added as needed, e.g., xSpeed/ySpeed/zSpeed.
    public MyQuadParticle(ClientLevel level, double x, double y, double z, SpriteSet spriteSet) {
        // Initial sprite set in constructor
        super(level, x, y, z, spriteSet.first());
        this.spriteSet = spriteSet;
        this.gravity = 0; // Our particle floats in midair now, because why not.
    }

    @Override
    public void tick() {
        // Let super handle movement.
        // You may replace this with your own movement if needed.
        // You may also override move() if you only want to modify the built-in movement.
        super.tick();

        // Set the sprite for the current particle age, i.e. advance the animation.
        this.setSpriteFromAge(this.spriteSet);
    }

    @Override
    protected abstract SingleQuadParticle.Layer getLayer() {
        // Sets the layer used to get and submit the texture.
        return EXAMPLE_LAYER;
    }
}
```

:::warning
Particles whose `SingleQuadParticle.Layer` uses `TextureAtlas#LOCATION_PARTICLES` must have an associated [particle description][description]. Otherwise, the textures required by the particle will not be added to the atlas.
:::

## Particle Groups and Render States

If a particle requires something more complex than a quad, then it will need its own `ParticleGroup<P>`, where `P` is the type of the `Particle`. `ParticleGroup`s are responsible for ticking a defined subset of `Particle`s, removing them once `Particle#isAlive` returns false. Each group can queue up to 16,384 particles, evicting the oldest once full. 

```java
// Let's assume we have the following particle class
public class ComplexParticle extends Particle {

    // You are not required to use these fields or store these values.
    // It is up to you to determine what you wish to render and get the
    // appropriate data.
    private final Model.Simple model;
    private final SpriteId sprite;

    public ComplexParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        this.model = StandingSignRenderer.createSignModel(
            Minecraft.getInstance().getEntityModels(), WoodType.OAK, PlainSignBlock.Attachment.GROUND
        );
        this.sprite = Sheets.getSignSprite(WoodType.OAK);
    }

    public Model.Simple model() {
        return this.model;
    }

    public SpriteId sprite() {
        return this.sprite;
    }
}

// We can create a basic particle group like so
public class ComplexParticleGroup extends ParticleGroup<ComplexParticle> {

    public ComplexParticleGroup(ParticleEngine engine) {
        super(engine);
    }

    // ...
}
```

Once a `Particle` has been added to the `ParticleGroup`, it is extracted during [feature submission][features] to a `ParticleGroupRenderState` via `ParticleGroup#extractRenderState`. `ParticleGroupRenderState` is a mix between a render state containing the extracted particle and a handler to submit the particle elements for rendering (via `#submit`).

```java
// The particle group render state
public record ComplexParticleRenderState(List<ComplexParticleRenderState.Entry> entries) implements ParticleGroupRenderState {

    // Each entry represents a particle in the group
    public record Entry(Model.Simple model, SpriteId sprite, PoseStack pose) {}

    @Override
    public void submit(SubmitNodeCollector collector, CameraRenderState camera) {
        // Submit the particle elements to render
        for (ComplexParticleRenderState.Entry entry : this.entries) {
            collector.submitModel(...);
        }
    }
}

// And in the group...
public class ComplexParticleGroup extends ParticleGroup<ComplexParticle> {

    // ...

    @Override
    public ParticleGroupRenderState extractRenderState(Frustum frustum, Camera camera, float partialTickTime) {
        // Extract the render state from the particles
        List<ComplexParticleRenderState.Entry> entries = new ArrayList<>();

        for (ComplexParticle particle : this.particles) {
            PoseStack pose = new PoseStack();
            pose.pushPose();
            pose.mulPose(camera.rotation());
            entries.add(new ComplexParticleRenderState.Entry(particle.model(), particle.sprite(), pose));
        }

        return new ComplexParticleRenderState(entries);
    }
}
```

On its own, a `Particle` does not know what `ParticleGroup` it belongs to, nor does the `ParticleEngine` know that the group exists. These are all linked together using a `ParticleRenderType`: a unique identifier for the group. The `ParticleRenderType` is linked to the `ParticleGroup` via the [client-side][side] [mod bus][modbus] [event] `RegisterParticleGroupsEvent`. Then, a `Particle` can use the group by setting `Particle#getGroup` to the created type.

```java
// Create the render type
// The string passed in should be a stringified `Identifier`
public static final ParticleRenderType COMPLEX = new ParticleRenderType("examplemod:complex");

@SubscribeEvent // on the mod event bus only on the physical client
public static void registerParticleProviders(RegisterParticleGroupsEvent event) {
    // Link the render type to the particle group
    event.register(COMPLEX, ComplexParticleGroup::new);
}

public class ComplexParticle extends Particle {

    // ...

    @Override
    public ParticleRenderType getGroup() {
        // Tell the particle to render using the particle group
        return COMPLEX;
    }
}
```

## `ParticleProvider`

Once a particle for some particle type has been created, the particle type must be linked through a `ParticleProvider`. `ParticleProvider` is a client-only class responsible for actually creating our `Particle`s from the `ParticleEngine` via `createParticle`. While more elaborate code can be included here, many particle providers are as simple as this:

```java
// The generic type of ParticleProvider must match the type of the particle type this provider is for.
public class MyQuadParticleProvider implements ParticleProvider<SimpleParticleType> {

    // A set of particle sprites.
    private final SpriteSet spriteSet;

    // The registration function passes a SpriteSet, so we accept that and store it for further use.
    // If your particle does not require a SpriteSet, this constructor can be omitted.
    public MyParticleProvider(SpriteSet spriteSet) {
        this.spriteSet = spriteSet;
    }

    // This is where the magic happens. We return a new particle each time this method is called!
    // The type of the first parameter matches the generic type passed to the super interface.
    @Override
    @Nullable
    public Particle createParticle(SimpleParticleType particleType, ClientLevel level, double x, double y, double z, double xd, double yd, double zd, RandomSource random
    ) {
        // We don't use the type, speed deltas, or engine random.
        return new MyQuadParticle(level, x, y, z, this.spriteSet);
    }
}
```

Your particle provider must then be associated with the particle type in the [client-side][side] [mod bus][modbus] [event] `RegisterParticleProvidersEvent`:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
    // There are multiple ways to register providers, all differing in the functional type they provide in the
    // second parameter. For example, #registerSpriteSet represents a Function<SpriteSet, ParticleProvider<?>>:
    event.registerSpriteSet(MyParticleTypes.MY_QUAD_PARTICLE.get(), MyQuadParticleProvider::new);

    // #registerSpecial, on the other hand, maps to a ParticleProvider<?>.
    // This should be used if the sprite is not obtained from the particle description.
}
```

:::warning
If `registerSpriteSet` is used, then the particle type must also have an associated [particle description][description]. Otherwise, an exception will be thrown stating it 'Failed to load description'.
:::

[description]: ../resources/client/particles.md
[event]: ../concepts/events.md
[features]: feature.md
[modbus]: ../concepts/events.md#event-buses
[particletype]: ../resources/client/particles.md
[registry]: ../concepts/registries.md#methods-for-registering
[side]: ../concepts/sides.md

## rendering/screens

# Screens

Screens are typically the base of all Graphical User Interfaces (GUIs) in Minecraft: taking in user input, verifying it on the server, and syncing the resulting action back to the client. They can be combined with [menus] to create an communication network for inventory-like views, or they can be standalone which modders can handle through their own [network] implementations.

Screens are made up of numerous parts, making it difficult to fully understand what a 'screen' actually is in Minecraft. As such, this document will go over each of the screen's components and how it is applied before discussing the screen itself.

## Rendering a GUI

Rendering a GUI takes place in two steps: the submission phase and the render phase.

The submission phase is responsible for collecting all elements (e.g. buttons, text, items) to render to the screen. Each submission will be stored in the `GuiRenderState` to be processed and then rendered during the render phase. There are four types of elements provided by vanilla: `GuiElementRenderState`, `GuiItemRenderState`, `GuiTextRenderState`, and `PictureInPictureRenderState`. Everything discussed in the below sections takes place during the submission phase by internally creating one of the above render states.

The render phase, as the name implies, renders the elements to the screen. First, `PictureInPictureRenderState`, `GuiItemRenderState`, and `GuiTextRenderState` are prepared and processed into `GuiElementRenderState`s. Then, the elements are sorted before being finally drawn to the screen. Finally, the `GuiRenderState` is reset and ready to be used for the next GUI or render tick.

### Relative Coordinates

Whenever anything is submitted to the render state, there needs to be some coordinates which specifies where the element will be rendered. With numerous abstractions, most of Minecraft's rendering calls accept X and Y coordinates. X values increase from left to right, while Y values increase from top to bottom. However, the coordinates are not fixed to a specified range. Their range can change depending on the size of the screen and the GUI scale specified within the game’s options. As such, extra care must be taken to ensure the coordinates values passed to rendering calls scale properly - are relativized correctly - to the changeable screen size.

Information on how to relativize your coordinates is in the [screen] section.

:::caution
If you choose to use fixed coordinates or incorrectly scale the screen, the rendered objects may look strange or misplaced. An easy way to check if you relativized your coordinates correctly is to click the 'Gui Scale' button in your video settings. This value is used as the divisor to the width and height of your display when determining the scale at which a GUI should render.
:::

### GuiGraphicsExtractor

Any element submitted to the `GuiRenderState` is typically handled via `GuiGraphicsExtractor`. `GuiGraphicsExtractor` is the first parameter to almost every method during the submission phase, containing methods for submitting commonly used objects to be rendered.

`GuiGraphicsExtractor` exposes the current pose as a `Matrix3x2fStack` to apply any XY transformations:

```java
// For some GuiGraphicsExtractor graphics

// Push a new matrix onto the stack
graphics.pose().pushMatrix();

// Apply the transformations you want the element to render with

// Takes in some XY offset
graphics.pose().translate(10, 10);
// Takes in some rotation angle in radians
graphics.pose().rotate((float) Math.PI);
// Takes in some XY scalar
graphics.pose().scale(2f, 2f);

// Submit elements to the `GuiRenderState`
graphics.blitSprite(...);

// Pop the matrix to reset the transformations
graphics.pose().popMatrix();
```

Additionally, elements can be cropped to a specific area using `enableScissor` and `disableScissor`:

```java
// For some GuiGraphicsExtractor graphics

// Enable the scissor with the bounds to render within
graphics.enableScissor(
    // The left X coordinate
    0,
    // The top Y coordinate
    0,
    // The right X coordinate
    10,
    // The bottom Y coordinate
    10
);

// Submit elements to the `GuiRenderState`
graphics.blitSprite(...);

// Disable the scissor to reset the rendering area
graphics.disableScissor();
```

### Node Trees and Strata

When submitting an element to the `GuiRenderState`, it isn't just added to some list. If that were the case, some elements may be completely covered by other elements depending on the order of submission. To get around this hurdle, elements are initially sorted into node trees in some stratum. How an element is sorted is based upon its defined `ScreenArea#bounds`; otherwise, the element will not be submitted for rendering.

The `GuiRenderState` is made up of `GuiRenderState.Node`s as a single-linked list, using 'up' to hold a reference to the next element. Nodes are rendered from first element 'up'wards. Each node holds its own layer data containing the render states. When an element is initially submitted to `GuiRenderState`, it determines what node to use or create based upon its defined `ScreenArea#bounds`. The node chosen, or created, is one node above the highest node with intersecting elements.

:::warning
Although `ScreenArea#bounds` is marked as nullable, a element being submitted to the render state will not be added if the bounds is not defined. The method is only nullable as elements submitted during the render phase are added to the current node rather than computing its node based on the bounds.
:::

Each node list is known as a stratum within the render state. A render state can have multiple strata by calling `GuiGraphicsExtractor#nextStratum`, creating a new node list. The new stratum will render above all the previous stratum's elements (e.g., item tooltips). You cannot navigate back to the previous stratum once you call `nextStratum`.

### `GuiElementRenderState`

A `GuiElementRenderState` holds the metadata on how a GUI element is rendered to the screen. The element render state extends `ScreenArea` to define the `bounds` on the screen. The bounds should always encompass the entire element that is rendered so that it's sorted correctly in the node list. Bounds computation typically takes in some of the parameters below, including the position and pose.

`scissorArea` crops the area where the element can render. If `scissorArea` is `null`, then the entire element is rendered to the screen. Similarly, if the `scissorArea` rectangle does not intersect with the `bounds`, then nothing will be rendered.

The remaining three methods handle the actual rendering of the element. `pipeline` defines the shaders and metadata used by the element. `textureSetup` can specify either `Sampler0`, `Sampler1`, `Sampler2`, or some combination in the fragment shader. Finally, `buildVertices` passes the vertices to upload to the buffer. It takes in the `VertexConsumer` to pass the vertices to.

NeoForge adds the method `GuiGraphicsExtractor#submitGuiElementRenderState` to submit a custom element render state if the available methods provided by `GuiGraphicsExtractor` is not enough.

```java
// For some GuiGraphicsExtractor graphics
graphics.submitGuiElementRenderState(new GuiElementRenderState() {

    // Store the current pose of the stack
    private final Matrix3x2f pose = new Matrix3x2f(graphics.pose());
    // Store the current scissor area
    @Nullable
    private final ScreenRectangle scissorArea = graphics.peekScissorStack();

    @Override
    public ScreenRectangle bounds() {
        // We will assume the bounds is 0, 0, 10, 10
        
        // Compute the initial rectangle
        ScreenRectangle rectangle = new ScreenRectangle(
            // The XY position
            0, 0,
            // The width and height of the element
            10, 10
        );

        // Transform the rectangle to its appropriate location using the pose
        rectangle = rectangle.transformMaxBounds(this.pose);

        // If there is a scissor area defined, return the intersection of the two rectangles
        // Otherwise, return the full bounds
        return this.scissorArea != null
            ? this.scissorArea.intersection(rectangle)
            : rectangle;
    }

    @Override
    @Nullable
    public ScreenRectangle scissorArea() {
        return this.scissorArea;
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        // Returns the textures to be used by the samplers in a fragment shader
        // When used by the fragment shader:
        // - Sampler0 typically contains the element texture
        // - Sampler1 typically provides a second element texture, currently only used by the end portal pipeline
        // - Sampler2 typically contains the game's lightmap texture

        // Should generally specify at least one texture in Sampler0
        return TextureSetup.noTexture();
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        // Build the vertices using the vertex format specified by the pipeline
        // For GUI, uses quads with position and color
        // Color must be in ARGB format
        consumer.addVertexWith2DPose(this.pose, 0,   0).setUv(0, 0).setColor(0xFFFFFFFF);
        consumer.addVertexWith2DPose(this.pose, 0,  10).setUv(0, 1).setColor(0xFFFFFFFF);
        consumer.addVertexWith2DPose(this.pose, 10, 10).setUv(1, 1).setColor(0xFFFFFFFF);
        consumer.addVertexWith2DPose(this.pose, 10,  0).setUv(1, 0).setColor(0xFFFFFFFF);
    }
});
```

### Element Ordering

So far, the elements shown above have only been operating on XY coordinates. The Z coordinate is ignored in GUI rendering, as all of the elements drawn to the screen use a `RenderPipeline` that disables the depth test. Even the 3D elements with their more advanced pipelines are drawn to a 2D texture using `RenderPipeline#GUI_TEXTURED_PREMULTIPLIED_ALPHA` by default, which does the same, preventing any Z-fighting in common use cases.

As such, during the render phase, each stratum is rendered in order, with the nodes in the node list rendered from the first element 'up'wards. But what about within a given node? This is handled via the `GuiRenderer#ELEMENT_SORT_COMPARATOR`, which sorts elements based on their `GuiElementRenderState#scissorArea`, `pipeline`, then `textureSetup`.

:::warning
Glyphs rendered for text are not sorted and will always render after all elements in the current node.
:::

Elements with no specified `scissorArea` will always be rendered first, followed by the top Y, the bottom Y, the left X, and finally the right X. If the `scissorArea` for two elements match, the sort key of the `pipeline` (via `RenderPipeline#getSortKey`) will be used. The sort key is based on the order that the `RenderPipeline`s are built in, which in vanilla is the classloading of static constants within `RenderPipelines`. If the sort keys match, then the `textureSetup` is used. Elements with no specified `textureSetup` are ordered first, followed by the sort key (via `TextureSetup#getSortKey`) of texture elements.

:::warning
On a technical level, element ordering is not deterministic due to the `RenderPipeline` and `TextureSetup`. This is because the goal of sorting is not determinism, but rather to render the elements with the least amount of pipeline and texture switches possible.
:::

## Methods in `GuiGraphicsExtractor`

`GuiGraphicsExtractor` contains methods used to submit commonly used objects for rendering. These fall into six categories: colored rectangles, strings, textures, items, tooltips, and picture-in-pictures. Each of these methods submit an element, inheriting the current pose from `pose` and the scissor area from `peekScissorStack` based on `enableScissor` / `disableScissor`. Any colors provided to the methods must be in [ARGB][argb] format.

### Colored Rectangles

Colored rectangles are submitted using a `ColoredRectangleRenderState`. All fill methods can take in an optional `RenderPipeline` and `TextureSetup` to specify how the rectangle should be rendered. There are three types of colored rectangles that can be submitted.

First, there is a colored horizontal and vertical one-pixel wide line, `horizontalLine` and `verticalLine` respectively. `horizontalLine` takes in two X coordinates defining the left and right (inclusively), the top Y coordinate, and the color. `verticalLine` takes in the left X coordinate, two Y coordinates defining the top and bottom (inclusively), and the color.

Second, there is the `fill` method, which submits a rectangle to be drawn to the screen. The line methods internally call this method. This takes in the left X coordinate, the top Y coordinate, the right X coordinate, the bottom Y coordinate, and the color.

Third, there is the `outline` method, which submits four rectangles that are one-pixel wide to act as an outline. This takes in the left X coordinate, the top Y coordinate, the width of the outline, the height of the outline, and the color.

Finally, there is the `fillGradient` method, which draws a rectangle with a vertical gradient. This takes in the left X coordinate, the top Y coordinate, the right X coordinate, the bottom Y coordinate, and the bottom and top colors.

### Strings

Strings, [`Component`s][component], and `FormattedCharSequence`s are submitted using a `GuiTextRenderState`. Each string is drawn through the provided `Font`, which is used to create a `BakedGlyph.GlyphInstance` and optionally a `BakedGlyph.Effect`, using the specified `GlyphRenderTypes#guiPipeline`. The text render state is then transformed into `GlyphRenderState`s and potentially a `GlyphEffectRenderState` per character in the string during the render phase.

There are two alignments strings can be rendered with: a left-aligned string (`text`) and a center-aligned string (`centeredText`). These both take in the font the string will be rendered in, the string to draw, the X coordinate representing the left or center of the string respectively, the top Y coordinate, and the color. The left-aligned strings may also take in whether to draw a drop shadow for the text.

If the text should be wrapped within a given bounds, then `textWithWordWrap` can be used instead. If the text should have some sort of rectangle backdrop, then `textWithBackdrop` can be used. They both submit a left-aligned string by default.

Strings can also be submitted using an `ActiveTextCollector`, which provides methods for rendering strings with specific metadata, such as alignment, opacity, and scrolling. Text collectors are created via `GuiGraphicsExtractor#textRenderer` or `textRendererForWidget`, or `ActiveTextCollector` itself can be subclassed, typically taking in a `$HoveredTextEffects` for some basic options on whether to render tooltips or cursor changes. From there, either `accept` or `acceptScrolling` can be used to render the text, taking in an X position relative to the alignment, a Y position, a set of parameters from the `GuiGraphicsExtractor`, the text itself, and optionally the text alignment. `acceptScrolling` also takes in the leftmost, rightmost, topmost, and bottommost position to represent the scrolling bounds.

:::note
Strings should typically be passed in as [`Component`s][component] as they handle a variety of use cases, including the two other overloads of the method.
:::

### Textures

Textures are submitted through a `BlitRenderState`, hence the method name `blit`. The `BlitRenderState` copies the bits of an image and renders them to the screen through the `RenderPipeline` parameter. Each `blit` also takes in a `Identifier`, which represents the absolute location of the texture:

```java
// Points to 'assets/examplemod/textures/gui/container/example_container.png'
private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("examplemod", "textures/gui/container/example_container.png");
```

While there are many different `blit` overloads, we will only discuss two of them.

The first `blit` takes in two integers, then two floats, and finally four more integers, assuming the image is on a PNG file. It takes in the left X and top Y screen coordinate, the left X and top Y coordinate within the PNG, the width and height of the image to render, and the width and height of the PNG file.

:::tip
The size of the PNG file must be specified so that the coordinates can be normalized to obtain the associated UV values.
:::

The second `blit` adds an additional integer at the end which represents the tint color of the image to be drawn. If not specified, the tint color is `0xFFFFFFFF`.

#### `blitSprite`

`blitSprite` is a special implementation of `blit` where the texture is obtained from the GUI texture atlas. Most textures that overlay the background, such as the 'burn progress' overlay in furnace GUIs, are sprites. All sprite textures are relative to `textures/gui/sprites` and do not need to specify the file extension.

```java
// Points to 'assets/examplemod/textures/gui/sprites/container/example_container/example_sprite.png'
private static final Identifier SPRITE = Identifier.fromNamespaceAndPath("examplemod", "container/example_container/example_sprite");
```

One set of `blitSprite` methods have the same parameters as `blit`, except for the the four integers dealing with the coordinates, width, and height of the PNG.

The other `blitSprite` methods take in more texture information to allow for drawing a portion of the sprite. These methods take in the sprite width and height, the X and Y coordinate in the sprite, the left X and top Y screen coordinate, the tint color, and the width and height of the image to render.

If the sprite size does not match the texture size, then the sprite can be scaled in one of three ways: `stretch`, `tile`, and `nine_slice`. `stretch` stretches the image from the texture size to the screen size. `tile` renders the texture over and over again until it reaches the screen size. `nine_slice` divides the texture into one center, four edges, and four corners to tile the texture to the required screen size.

This is set by adding the `gui.scaling` JSON object in an mcmeta file with the same name of the texture file.

```json5
// For some texture file example_sprite.png
// In example_sprite.png.mcmeta

// Stretch example
{
    "gui": {
        "scaling": {
            "type": "stretch"
        }
    }
}

// Tile example
{
    "gui": {
        "scaling": {
            "type": "tile",
            // The size to begin tiling at
            // This is usually the size of the texture
            "width": 40,
            "height": 40
        }
    }
}

// Nine slice example
{
    "gui": {
        "scaling": {
            "type": "nine_slice",
            // The size to begin tiling at
            // This is usually the size of the texture
            "width": 40,
            "height": 40,
            "border": {
                // The padding of the texture that will be sliced into the border texture
                "left": 1,
                "right": 1,
                "top": 1,
                "bottom": 1
            },
            // When true the center part of the texture will be applied like
            // the stretch type instead of a nine slice tiling.
            "stretch_inner": true
        }
    }
}
```

:::note
`blitSprite` when using a texture with tiling or nice slice set will submit their elements using `TiledBlitRenderState`, which specifies the tile width and height in addition to the other parameters in `BlitRenderState`.
:::

### Items

Items are submitted using a `GuiItemRenderState`. The item render state is then transformed into a `BlitRenderState` or `OversizedItemRenderState`, depending on the item bounds and client item properties, during the render phase.

`item` takes in an `ItemStack`, in addition to the left X and top Y coordinate on screen. It can optionally take in the holding `LivingEntity`, the current `Level` the stack is in, and a seeded value. There is also an alternative `fakeItem` which sets the `LivingEntity` to `null`.

The item decorations - such as the durability bar, cooldown, and count - are handled through `itemDecorations`. It takes in the same parameters as the base `item`, in addition to the `Font` and a count text override.

### Tooltips

Tooltips are submitted through a variety of the above render states. The tooltip methods are broken into two categories: 'next frame' and 'immediate'. Both methods takes in the `Font` to render the text, some list of `Component`s, an optional `TooltipComponent` for special rendering, the left X and top Y, a `ClientTooltipPositioner` for adjusting the location, and the background and frame texture.

Next frame tooltips don't actually submit the tooltip on the next frame, but instead defer the tooltip submission until after `Screen#render` is called. The tooltip is added to a new stratum, meaning it will render on top of all elements in the screen. Next frame methods are in the form of `set*Tooltip*ForNextFrame`. They also can take in an additional boolean indicating whether to override the currently deferred tooltip if present, and an `ItemStack` that the rendered tooltip should use.

Immediate tooltips, on the other hand, are submitted immediately when the method is called. Immediate methods are in the form of `tooltip`. They also take in the `ItemStack` that the tooltip is hovering over.

### Picture-in-Picture

Picture-in-Picture (PiP) allows for arbitrary objects to be drawn to the screen. Instead of drawing directly to the output, PiP draws the object to an intermediary texture, or a 'picture', that is then submitted to the `GuiRenderState` as a `BlitRenderState` during the render phase (by default). `GuiGraphicsExtractor` provides methods for maps (`map`), entities (`entity`), player skins (`skin`), book models (`book`), banner pattern (`bannerPattern`), signs (`sign`), and the profiler chart (`profilerChart`).

:::note
Items that exceed the default 16x16 bounds, when `ClientItem.Properties#oversizedInGui` is true, use the `OversizedItemRenderer` PiP as its rendering mechanism.
:::

Each PiP submits a `PictureInPictureRenderState` to render an object to the screen. Similarly to `GuiElementRenderState`, `PictureInPictureRenderState` also extends `ScreenArea` to define its `bounds` and the scissor via `scissorArea`. `PictureInPictureRenderState` then defines the render location and size of the picture, specifying the left X (`x0`), the right X (`x1`), the top Y (`y0`), and the bottom Y (`y1`). The element within the picture can also be `scale`d by some float value. Finally, an additional `pose` can be used to transform the XY coordinates of the picture. By default, this is the identity pose as generally, the rendered object is already transformed within the picture itself. For ease of implementation, the `bounds` can be computed using `PictureInPictureRenderState#getBounds`, though if the `pose` is modified, you will need to implement your own logic.

```java
// Other parameters can be added, but this is the minimum required to implement all methods
public record ExampleRenderState(
    int x0, // The left X
    int x1, // The right X
    int y0, // The top Y
    int y1, // The bottom Y
    float scale, // The scale factor when drawing to the picture
    @Nullable ScreenRectangle scissorArea, // The rendering area
    @Nullable ScreenRectangle bounds // The bounds of the element
) implements PictureInPictureRenderState {

    // Additional constructors
    public ExampleRenderState(int x, int y, int width, int height, @Nullable ScreenRectangle scissorArea) {
        this(
            x, // x0
            x + width, // x1
            y, // y0
            y + height, // y1
            1f, // scale
            scissorArea,
            PictureInPictureRenderState.getBounds(x, y, x + width, y + height, scissorArea)
        );
    }
}
```

To draw and submit the PiP render state to a picture, each PiP has its own `PictureInPictureRenderer<T>`, where `T` is the implemented `PictureInPictureRenderState`. There are numerous methods that can be overridden, allowing the user almost full control of the entire pipeline, but there are three that must be implemented.

First is `getRenderStateClass`, which simply returns the class of the `PictureInPictureRenderState`. In vanilla, this method was used to register what render state the renderer was used for. NeoForge still uses the render state class, but provides registration through an event to map to a dynamic pool of renderers instead of calling `getRenderStateClass`.

Then, there is `getTextureLabel`, which provides a unique debug label for the picture being written to. Finally, there is `renderToTexture`, which actually draws the object to the picture, similar to other render methods.

```java
public class ExampleRenderer extends PictureInPictureRenderer<ExampleRenderState> {

    // Takes in the buffers used to write the object to the picture
    public ExampleRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<ExampleRenderState> getRenderStateClass() {
        // Returns the render state class
        return ExampleRenderState.class;
    }

    @Override
    protected String getTextureLabel() {
        // Can be any string, but should be unique
        // Prefix with mod id for greater clarity
        return "examplemod: example pip";
    }

    @Override
    protected void renderToTexture(ExampleRenderState renderState, PoseStack pose) {
        // Modify pose if desired
        // Can push/pop if wanted, but a new `PoseStack` is created for writing to the picture
        pose.translate(...);

        // Render the object to the screen
        VertexConsumer consumer = this.bufferSource.getBuffer(RenderType.lines());
        consumer.addVertex(...).setColor(...).setNormal(...);
        consumer.addVertex(...).setColor(...).setNormal(...);
    }

    // Additional methods

    @Override
    protected void blitTexture(ExampleRenderState renderState, GuiRenderState guiState) {
        // Submits the picture to the gui render state as a `BlitRenderState` by default
        // Override this if you want to modify the `BlitRenderState`
        // Should call `GuiRenderState#submitBlitToCurrentLayer`
        // Bounds can be `null`
        super.blitTexture(renderState, guiState);
    }

    @Override
    protected boolean textureIsReadyToBlit(ExampleRenderState renderState) {
        // When true, this reuses the already written-to picture instead of
        // constructing a new picture and writing to it using `renderToTexture`.
        // This should only be true if it is guaranteed that two elements will
        // be rendered *exactly* the same.
        return super.textureIsReadyToBlit(renderState);
    }

    @Override
    protected float getTranslateY(int scaledHeight, int guiScale) {
        // Sets the initial offset the `PoseStack` is translated by in the Y direction.
        // Common implementations use `scaledHeight / 2f` to center the Y coordinate similar to X.
        return scaledHeight;
    }

    @Override
    public boolean canBeReusedFor(ExampleRenderState state, int textureWidth, int textureHeight) {
        // A NeoForge-added method used to check if this renderer can be reused on a subsequent frame.
        // When true, this will reuse the constructed state and renderer from the previous frame.
        // When false, a new renderer will be created.
        return super.canBeReusedFor(state, textureWidth, textureHeight);
    }
}
```

To use the PiP, the renderer must be registered to `RegisterPictureInPictureRenderersEvent` on the [mod event bus][modbus].

```java
@SubscribeEvent // on the mod event bus
public static void registerPip(RegisterPictureInPictureRenderersEvent event) {
    event.register(
        // The PiP render state class
        ExampleRenderState.class,
        // A factory that takes in the `MultiBufferSource.BufferSource` and returns the PiP renderer
        ExampleRenderer::new
    );
}
```

The PiP render state can then be submitted using the NeoForge-added `GuiGraphicsExtractor#submitPictureInPictureRenderState`:

```java
// For some GuiGraphicsExtractor graphics
graphics.submitPictureInPictureRenderState(new ExampleRenderState(
    0, 0,
    10, 10,
    // Get the scissor area from the stack
    graphics.peekScissorStack()
));
```

:::note
NeoForge fixes a bug that prevents multiple instances of a PiP render state to be submitted for any given frame.
:::

## Renderable

`Renderable`s are essentially objects that are rendered. These include screens, buttons, chat boxes, lists, etc. `Renderable`s only have one method: `#extractRenderState`. This takes in the `GuiGraphicsExtractor` used to submit elements to the screen, the x and y positions of the mouse scaled to the relative screen size, and the tick delta (how many ticks have passed since the last frame).

Some common renderables are screens and 'widgets': interactable elements such as `Button`, its subtype `ImageButton`, and `EditBox` which is used to input text on the screen.

## GuiEventListener

Any screen in Minecraft implements `GuiEventListener`. `GuiEventListener`s are responsible for handling user interaction with the screen. These include inputs from the mouse (movement, clicked, released, dragged, scrolled, mouseover) and keyboard (pressed, released, typed). Each method returns whether the associated action affected the screen successfully. Widgets like buttons, chat boxes, lists, etc. also implement this interface.

### ContainerEventHandler

Almost synonymous with `GuiEventListener`s are their subtype: `ContainerEventHandler`s. These are responsible for handling user interaction on screens which contain widgets, managing which is currently focused and how the associated interactions are applied. `ContainerEventHandler`s add three additional features: interactable children, dragging, and focusing.

Event handlers hold children which are used to determine the interaction order of elements. During the mouse event handlers (excluding dragging), the first child in the list that the mouse hovers over has their logic executed.

Dragging an element with the mouse, implemented via `#mouseClicked` and `#mouseReleased`, provides more precisely executed logic.

Focusing allows for a specific child to be checked first and handled during an event's execution, such as during keyboard events or dragging the mouse. Focus is typically set through `#setFocused`. In addition, interactable children can be cycled using `#nextFocusPath`, selecting the child based upon the `FocusNavigationEvent` passed in.

:::note
Screens implement `ContainerEventHandler` through `AbstractContainerEventHandler`, which adds in the setter and getter logic for dragging and focusing children.
:::

## NarratableEntry

`NarratableEntry`s are elements which can be spoken about through Minecraft's accessibility narration feature. Each element can provide different narration depending on what is hovered or selected, prioritized typically by focus, hovering, and then all other cases.

`NarratableEntry`s have four methods: two which determine the priority of the element when being read (`#narrationPriority` and `#getTabOrderGroup`), one which determines whether to speak the narration (`#isActive`), and finally one which supplies the narration to its associated output, spoken or read (`#updateNarration`). 

:::note
All widgets from Minecraft are `NarratableEntry`s, so it typically does not need to be manually implemented if using an available subtype.
:::

## The Screen Subtype

With all of the above knowledge, a basic screen can be constructed. To make it easier to understand, the components of a screen will be mentioned in the order they are typically encountered.

First, all screens take in a `Component` which represents the title of the screen. This component is typically drawn to the screen by one of its subtypes. It is only used in the base screen for the narration message. The screen can also take in the `Minecraft` instance and the `Font` to use when rendering text; if not specified, the default instance and font are used.

```java
// In some Screen subclass
public MyScreen(Component title) {
    super(Minecraft.getInstance(), Minecraft.getInstance().font, title);
}
```

### Initialization

Once a screen has been initialized, the `#init` method is called. The `init` method sets the initial settings inside the screen from the `Minecraft` instance to the relative width and height as scaled by the game. Any setup such as adding widgets or precomputing relative coordinates should be done in this method. If the game window is resized, the screen will be reinitialized by calling the `init` method.

There are three ways to add a widget to a screen, each serving a separate purpose:

| Method               | Description                                                                   |
|:--------------------:|:------------------------------------------------------------------------------|
|`addWidget`           | Adds a widget that is interactable and narrated, but not rendered.            |
|`addRenderableOnly`   | Adds a widget that will only be rendered; it is not interactable or narrated. |
|`addRenderableWidget` | Adds a widget that is interactable, narrated, and rendered.                   |

Typically, `addRenderableWidget` will be used most often.

```java
// In some Screen subclass
@Override
protected void init() {
    super.init();

    // Add widgets and precomputed values
    this.addRenderableWidget(new EditBox(/* ... */));
}
```

### Ticking Screens

Screens also tick using the `#tick` method to perform some level of client side logic for rendering purposes.

```java
// In some Screen subclass
@Override
public void tick() {
    super.tick();

    // Execute some logic every frame
}
```

### Input Handling

Since screens are subtypes of `GuiEventListener`s, the input handlers can also be overridden, such as for handling logic on a specific [key press][keymapping].

### Rendering the Screen

Screens submit their elements for rendering through `#extractRenderStateWithTooltipAndSubtitles` in three different strata: the background stratum, the element stratum, and the optional hoverable strata.

The background stratum elements are submitted first via `#extractBackground`, generally containing any blurring or background textures.

:::warning
Blurring, as handled through `GuiGraphicsExtractor#blurBeforeThisStratum`, can only be called once on any given frame. Attempting to submit a second blur will cause an exception to be thrown.
:::

The element stratum elements are submitted next via the `#extractRenderState` method, provided by being a `Renderable` subtype. This mainly submits widgets and labels, along with setting the hoverables to submit.

Finally, the hoverable strata submit elements that hover over the previous elements, such as tooltips.

```java
// In some Screen subclass

// mouseX and mouseY indicate the scaled coordinates of where the cursor is in on the screen
@Override
public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    // Submit things on the background stratum
    this.extractTransparentBackground(graphics);
}

@Override
public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    // Submit things before widgets

    // Then the widgets if this is a direct child of the Screen
    super.extractRenderState(graphics, mouseX, mouseY, partialTick);

    // Submit things after widgets

    // Set the tooltip to be added above everything in this method
    graphics.setTooltipForNextFrame(...);
}
```

### Closing the Screen

When a screen is closed, two methods handle the teardown: `#onClose` and `#removed`.

`onClose` is called whenever the user makes an input to close the current screen. This method is typically used as a callback to destroy and save any internal processes in the screen itself. This includes sending packets to the server.

`removed` is called just before the screen changes and is released to the garbage collector. This handles anything that hasn't been reset back to its initial state before the screen was opened.

```java
// In some Screen subclass

@Override
public void onClose() {
    // Stop any handlers here

    // Call last in case it interferes with the override
    super.onClose();
}

@Override
public void removed() {
    // Reset initial states here

    // Call last in case it interferes with the override
    super.removed()
;}
```

## `AbstractContainerScreen`

If a screen is directly attached to a [menu][menus], then an `AbstractContainerScreen` should be subclassed instead. An `AbstractContainerScreen` acts as the screen and input handler of a menu and contains logic for syncing and interacting with slots. As such, only two methods typically need to be overridden or implemented to have a working container screen. Once again, to make it easier to understand, the components of a container screen will be mentioned in the order they are typically encountered.

An `AbstractContainerScreen` typically requires five parameters: the container menu being opened (represented by the generic `T`), the player inventory (only for the display name), the title of the screen itself, and the width and height of the background texture.

:::note
The background texture width and height can be omitted from the super constructor if is 176 x 166. This does not refer to the image size, which is typically a PNG of 256 x 256, but the specific texture bounds within.
:::

Within here, a number of positioning fields can be set:

Field             | Description
:---:             | :---
`titleLabelX`     | The relative x coordinate of where the screen title will be rendered.
`titleLabelY`     | The relative y coordinate of where the screen title will be rendered.
`inventoryLabelX` | The relative x coordinate of where the player inventory name will be rendered.
`inventoryLabelY` | The relative y coordinate of where the player inventory name will be rendered.

:::caution
In a previous section, it was mentioned that precomputed relative coordinates should be set in the `#init` method. This still remains true, as the values mentioned here are not precomputed coordinates but static values and relativized coordinates.

The image values are static and non-changing, as they represent the background texture size. To make things easier when rendering, two additional values (`leftPos` and `topPos`) are precomputed in the `init` method, marking the top left corner of where the background will be rendered. The label coordinates are relative to these values.

The `leftPos` and `topPos` is also used as a convenient way to render the background as they already represent the position to pass into `GuiGraphicsExtractor#blit`.
:::

```java
// In some AbstractContainerScreen subclass
public MyContainerScreen(MyMenu menu, Inventory playerInventory, Component title) {
    super(menu, playerInventory, title, 176, 166);

    this.titleLabelX = 10;
    this.inventoryLabelX = 10;
}
```

### Menu Access

As the menu is passed into the screen, any values that were within the menu and synced (either through slots, data slots, or a custom system) can now be accessed through the `menu` field.

### Container Tick

Container screens tick within the `#tick` method when the player is alive and looking at the screen via `#containerTick`. This essentially takes the place of `tick` within container screens, with its most common usage being to tick the recipe book.

```java
// In some AbstractContainerScreen subclass
@Override
protected void containerTick() {
    super.containerTick();

    // Tick things here
}
```

### Rendering the Container Screen

The container screen uses all three strata to submit its elements. First, the background stratum submits the background texture by overriding `#extractBackground`. Then, the element stratum submits the widgets like before within `#extractContents`, followed by labels in `#extractLabels`. Finally, `AbstractContainerScreen` sets up the tooltip to be submitted during the hoverable strata via `extractTooltip`.

Starting with the background, `extractBackground` is called to submit the background elements of the screen to the background stratum.

```java
// In some AbstractContainerScreen subclass

// The location of the background texture (assets/<namespace>/<path>)
private static final Identifier BACKGROUND_LOCATION = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/container/my_container_screen.png");

@Override
protected void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractBackground(graphics, mouseX, mouseY, a);

    // Submits the background texture. 'leftPos' and 'topPos' should
    // already represent the top left corner of where the texture
    // should be rendered as it was precomputed from the 'imageWidth'
    // and 'imageHeight'. The two zeros represent the integer u/v
    // coordinates inside the PNG file, whose size is represented by
    // the last two integers (typically 256 x 256).
    graphics.blit(
        RenderPipelines.GUI_TEXTURED,
        BACKGROUND_LOCATION,
        this.leftPos, this.topPos,
        0, 0,
        this.imageWidth, this.imageHeight,
        256, 256
    );
}
```

`extractLabels` is called to submit any text after the widgets in the render stratum. This calls `text` with the screen font to submit the associated components.

```java
// In some AbstractContainerScreen subclass
@Override
protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    super.extractLabels(graphics, mouseX, mouseY);

    // Assume we have some Component 'label'
    // 'label' is drawn at 'labelX' and 'labelY'
    // The color is an ARGB value
    // The final boolean renders the drop shadow when true
    graphics.text(this.font, this.label, this.labelX, this.labelY, 0xFF404040, false);
}
```

:::note
When submitting the label, you do **not** need to specify the `leftPos` and `topPos` offset. Those have already been translated within the `Matrix3x2fStack` so everything within this method is submitted relative to those coordinates.
:::

## Registering an AbstractContainerScreen

To use an `AbstractContainerScreen` with a menu, it needs to be registered. This can be done by calling `register` within the `RegisterMenuScreensEvent` on the [**mod event bus**][modbus].

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerScreens(RegisterMenuScreensEvent event) {
    event.register(MY_MENU.get(), MyContainerScreen::new);
}
```

[menus]: ../inventories/menus.md
[network]: ../networking/index.md
[screen]: #the-screen-subtype
[argb]: https://en.wikipedia.org/wiki/RGBA_color_model#ARGB32
[component]: ../resources/client/i18n.md#components
[keymapping]: ../misc/keymappings.md#inside-a-gui
[modbus]: ../concepts/events.md#event-buses

## resources/client/i18n

# I18n and L10n

I18n (short for internationalization) is the way of designing a program to work with multiple languages. L10n (short for localization) is the process of translating text into the user's language. Minecraft implements these using `Component`s.

## `Component`s

A `Component` is a piece of text with metadata, with the metadata including things such as text formatting. It can be created in one of the following ways (all of the following are static methods in the `Component` interface):

| Method         | Description                                                                                           |
|----------------|-------------------------------------------------------------------------------------------------------|
| `empty`        | Creates an empty component.                                                                           |
| `literal`      | Creates a component with the given text and directly displays that text without translating.          |
| `nullToEmpty`  | Creates an empty component when given null, and a literal component otherwise.                        |
| `translatable` | Creates a translatable component. The given string is then resolved as a translation key (see below). |
| `keybind`      | Creates a component containing the (translated) display name of the given keybind.                    |
| `nbt`          | Creates a component representing the [NBT][nbt] at the given path.                                    |
| `score`        | Creates a component containing a scoreboard objective value.                                          |
| `selector`     | Creates a component containing a list of [entity] names for a given [entity selector][selector].      |
| `object`       | Creates a component containing an arbitrary font, like an atlas sprite.                               |

`Component.translatable()` additionally has a vararg parameter that accepts string interpolation elements. This works similar to Java's `String#format`, but always uses `%s` instead of `%i`, `%d`, `%f` and any other format specifier, calling `#toString()` where needed.

Every `Component` can be resolved using `#getString()`. Resolving is generally lazy, meaning that the server can specify a `Component`, send it to the clients, and the clients will each resolve the `Component`s on their own (where different languages may result in different text). Many places in Minecraft will also directly accept `Component`s and take care of resolving for you.

:::caution
Never let a server translate a `Component`. Always send `Component`s to the client and resolve them there.
:::

### `MutableComponent`

All constructed components are generally `MutableComponent`s. A `MutableComponent` provides methods to add new component siblings that are appended to the end of this component and set the [style] of the text. Constructing or modifying a component should always refer to `MutableComponent`s.

### Text Formatting

`Component`s can be formatted using `Style`s. `Style`s are immutable, creating a new `Style` object when modified, and thus allowing them to be created once and then be reused as needed.

:::note
Styles can only be set using methods on `MutableComponent`, so make sure to check that the `Component` is a `MutableComponent`.
:::

`Style.EMPTY` can generally be used as a base to work off. Multiple `Style`s can be merged with `Style#applyTo(Style other)`, which returns a new `Style` that takes settings from the `Style` the `applyTo()` method was called on unless the respective setting is absent, in which case the setting from the `Style` passed in as a parameter is used. `Style`s can then be applied to components like so:

```java
MutableComponent text = Component.literal("Hello World!");

// Create a new style.
Style blue = Style.EMPTY.withColor(0x0000FF);
Style greenShadow = Style.EMPTY.withShadowColor(0xFF00FF00);
// Styles use a builder-like pattern.
Style blueItalic = Style.EMPTY.withColor(0x0000FF).withItalic(true);
// Besides italic, we can also make styles bold, underlined, strikethrough, or obfuscated.
Style bold          = Style.EMPTY.withBold(true);
Style underlined    = Style.EMPTY.withUnderlined(true);
Style strikethrough = Style.EMPTY.withStrikethrough(true);
Style obfuscated    = Style.EMPTY.withObfuscated(true);
// Let's merge some styles together!
Style merged = blueItalic.applyTo(bold).applyTo(strikethrough);

// Set a style on a component.
text.setStyle(merged);
// Merge a new style into it.
text.withStyle(Style.EMPTY.withColor(0xFF0000));
```

Another, more elaborate option of formatting is to use click and hover events:

```java
// We have a total of 8 options for a click event, and a total of 3 options for a hover event.
ClickEvent clickEvent;
HoverEvent hoverEvent;

// Opens the given URL in your default browser when clicked.
clickEvent = new ClickEvent.OpenUrl(URI.create("http://example.com/"));
// Opens the given file when clicked. For security reasons, this cannot be sent from a server.
clickEvent = new ClickEvent.OpenFile("C:/example.txt");
// Runs the given command when clicked.
clickEvent = new ClickEvent.RunCommand("/gamemode creative");
// Suggests the given command in the chat when clicked.
clickEvent = new ClickEvent.SuggestCommand("/gamemode creative");
// Changes a book page when clicked. Irrelevant outside of a book screen context.
clickEvent = new ClickEvent.ChangePage("1");
// Copies the given text to the clipboard.
clickEvent = new ClickEvent.CopyToClipboard("Hello World!");
// Opens a dialog screen (assume we have some Holder<Dialog> dialog)
clickEvent = new ClickEvent.ShowDialog(dialog);
// Sends an identifier and payload to the server (by default, logs the action and payload sent)
clickEvent = new ClickEvent.Custom(Identifier.fromNamespaceAndPath("examplemod", "custom"), Optional.empty());

// Shows the given component when hovered. May be formatted as well.
// Keep in mind that click or hover events won't work in a hover tooltip.
hoverEvent = new HoverEvent.ShowText(Component.literal("Hello World!"));
// Shows a complete tooltip of the given stack template when hovered.
hoverEvent = new HoverEvent.ShowItem(new ItemStackTemplate(...));
// Shows a complete tooltip of the given entity when hovered.
// See the possible constructors of EntityTooltipInfo.
hoverEvent = new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(...));

// Apply the events to a style.
Style clickable = Style.EMPTY.withClickEvent(clickEvent);
Style hoverable = Style.EMPTY.withHoverEvent(hoverEvent);
```

Even the font used by the component can be set:

```java
// Set the font to be used by a style.
// Must match an available FontDescription.Resource (found in `assets/<namespace>/font/<path>.json`)

// Points to `assets/minecraft/font/illageralt.json`
Identifier fontLocation = Identifier.withDefaultNamespace("illageralt");

// Apply the font to a style
Style customFont = Style.EMPTY.withFont(new FontDescription.Resource(fontLocation));
```

:::warning
Font description types other than `FontDescription.Resource` will throw an exception when used in a style. To make use of other or custom `FontDescription`s, create and supply an `ObjectInfo` to a component with `ObjectContents`.
:::

## Language Files

Language files are JSON files that contain mappings from translation keys (see below) to actual names. They are located at `assets/<modid>/lang/language_name.json`. For example, US English translations for a mod with id `examplemod` would be located at `assets/examplemod/lang/en_us.json`. A full list of languages supported by Minecraft can be found [here][mcwikilang].

A language file generally looks like this:

```json
{
    "translation.key.1": "Translation 1",
    "translation.key.2": "Translation 2"
}
```

### Translation Keys

Translation keys are the keys used in translations. In many cases, they follow the format `registry.modid.name`. For example, a mod with the id `examplemod` that provides a block named `example_block` will probably want to provide translations for the key `block.examplemod.example_block`. However, you can use basically any string as a translation key.

If a translation key does not have an associated translation in the selected language, the game will fall back to US English (`en_us`), if that is not already the selected language. If US English does not have a translation either, the translation will fail silently, and the raw translation key will be displayed instead.

Some places in Minecraft offer you helper methods to get a translation keys. For example, both blocks and items provide `#getDescriptionId` methods. For items, these can not only be queried, but also changed if needed via `Item.Properties#overrideDescription`. If items have different names depending on their underlying [data components][datacomponent], these can be overriden by setting the `CUSTOM_NAME` data component with the desired translatable component on the `ItemStack`. There is also a variant on the `Item#getName` which takes in an [`ItemStack`][itemstack] parameter to set the default component of the item. `BlockItem`s, on the other hand, set the description id by calling `Item.Properties#useBlockDescriptionPrefix`. `Identifier`s also provide a way to construct the translation key using `toLanguageKey`, taking in the section or registry along with an optional suffix.

:::tip
The only purpose of translation keys is for localization. Do not use them for game logic, that's what [registry names][regname] are for.
:::

### Translating Mod Metadata

Translation files can override certain parts of [mod info][modstoml] using the following keys (where `modid` is to be replaced with the actual mod id):

|              | Translation Key                        | Overriding                                                                   |
|--------------|----------------------------------------|------------------------------------------------------------------------------|
| Description  | `fml.menu.mods.info.description.modid` | A field named `description` may be placed in the `[[mods]]` section instead. |

### Datagen

Language files can be [datagenned][datagen]. To do so, extend the `LanguageProvider` class and add your translations in the `addTranslations()` method:

```java
public class MyLanguageProvider extends LanguageProvider {

    public MyLanguageProvider(PackOutput output) {
        super(
            // Provided by the `GatherDataEvent.Client`.
            output,
            // Your mod id.
            "examplemod",
            // The locale to use. You may use multiple language providers for different locales.
            "en_us"
        );
    }
    
    @Override
    protected void addTranslations() {
        // Adds a translation with the given key and the given value.
        this.add("translation.key.1", "Translation 1");
        
        // Helpers are available for various common object types. Most helpers have two variants: an add() variant
        // for the object itself, and an addTypeHere() variant that accepts a supplier for the object.
        // The different names for the supplier variants are required due to generic type erasure.
        // All following examples assume the existence of the values as suppliers of the needed type.

        // Adds a block translation.
        this.add(MyBlocks.EXAMPLE_BLOCK.get(), "Example Block");
        this.addBlock(MyBlocks.EXAMPLE_BLOCK, "Example Block");
        // Adds an item translation.
        this.add(MyItems.EXAMPLE_ITEM.get(), "Example Item");
        this.addItem(MyItems.EXAMPLE_ITEM, "Example Item");
        // Adds an item stack translation. This is mainly for items that have NBT-specific names.
        this.add(MyItems.EXAMPLE_ITEM_STACK.get(), "Example Item");
        this.addItemStack(MyItems.EXAMPLE_ITEM_STACK, "Example Item");
        // Adds an entity type translation.
        this.add(MyEntityTypes.EXAMPLE_ENTITY_TYPE.get(), "Example Entity");
        this.addEntityType(MyEntityTypes.EXAMPLE_ENTITY_TYPE, "Example Entity");
        // Adds a mob effect translation.
        this.add(MyMobEffects.EXAMPLE_MOB_EFFECT.get(), "Example Effect");
        this.addEffect(MyMobEffects.EXAMPLE_MOB_EFFECT, "Example Effect");
        // Adds a tag key translation.
        this.add(MyTags.EXAMPLE_TAG, "Example Tag");
        // Adds a dimension translation.
        this.addDimension(MyDimensions.EXAMPLE_DIMENSION_KEY, "Example Dimension");
        // Adds a biome translation.
        this.addBiome(MyBiomes.EXAMPLE_BIOME_KEY, "Example Biome");
    }
}
```

Then, register the provider like any other provider in the `GatherDataEvent.Client`.

```java
@SubscribeEvent // on the mod event bus
public static void onGatherData(GatherDataEvent.Client event) {
    // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

    event.createProvider(MyLanguageProvider::new);
}
```

[datacomponent]: ../../items/datacomponents.md
[datagen]: ../index.md#data-generation
[entity]: ../../entities/index.md
[itemstack]: ../../items/index.md#itemstacks
[mcwikilang]: https://minecraft.wiki/w/Language
[modstoml]: ../../gettingstarted/modfiles.md#modstoml
[nbt]: ../../datastorage/nbt.md
[regname]: ../../concepts/registries.md
[selector]: https://minecraft.wiki/w/Target_selectors
[style]: #text-formatting

## resources/client/models/datagen

# Model Datagen

Like most JSON data, block and item models, along with their necessary blockstate files and [client items][citems], can be [datagenned][datagen]. This is all handled through the vanilla `ModelProvider`, with extensions provided by NeoForge via the `ExtendedModelTemplateBuilder`. Since the model JSON itself is similar between block and item models, the datagen code is relatively similar.

## Model Templates

Every model starts out as a `ModelTemplate`. For vanilla, the `ModelTemplate` acts as a parent to some pre-generated model file, defining the parent model, the required texture slots, and the file suffix to apply. For the NeoForge case, the `ExtendedModelTemplate` is constructed via an `ExtendedModelTemplateBuilder`, allowing the user to generate the model down to its base elements and faces, along with any NeoForge-added functionality.

A `ModelTemplate` is created by using one of the methods in `ModelTemplates` or calling the constructor. For the constructor, it takes in the optional `Identifier` of the parent model relative to the `models` directory, an optional string to apply to the end of file path (e.g., for a pressed button, it is suffixed with `_pressed`), and a varargs of `TextureSlot`s that must be defined for the datagen not to crash. `TextureSlot`s are just a string that define the 'key' of a texture in the `textures` map. Each key can also have a parent `TextureSlot` that it will resolve to if no texture is specified for the specific slot. For example, `TextureSlot#PARTICLE` will first look for a defined `particle` texture, then check for a defined `texture` value, and finally checking `all`. If the slot or its parents are not defined, then a crash is thrown during data generation.

```java
// Assumes there is a texture referenced as '#base'
// Can be resolved by either specifying 'base' or 'all'
public static final TextureSlot BASE = TextureSlot.create("base", TextureSlot.ALL);

// Assume there exists some model 'examplemod:block/example_template'
public static final ModelTemplate EXAMPLE_TEMPLATE = new ModelTemplate(
    // The parent model location
    Optional.of(
        ModelLocationUtils.decorateBlockModelLocation("examplemod:example_template")
    ),
    // The suffix to apply to the end of any model that uses this template
    Optional.of("_example"),
    // All texture slots that must be defined
    // Should be as specific as possible based on what's undefined in the parent model
    TextureSlot.PARTICLE,
    BASE
);
```

The NeoForge-added `ExtendedModelTemplate` can be constructed via `ExtendedModelTemplateBuilder#builder` or `ModelTemplate#extend` for an existing vanilla template. The builder can then be resolved into the template using `#build`. The builder's methods provide full control over the construction of the model JSON:

| Method                                           | Effect                                                                                                                                                                                                                                                                                                                                                  |
|--------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `#parent(Identifier parent)`                                         | Sets the parent model location relative to the `models` directory. |
| `#suffix(String suffix)`                                                   | Appends the string to the end of the model file path. |
| `#requiredTextureSlot(TextureSlot slot)`                                   | Adds a texture slot that must be defined within the `TextureMapping` for generation. |
| `transform(ItemDisplayContext type, Consumer<TransformVecBuilder> action)` | Adds a `TransformVecBuilder` that is configured via the consumer, used for setting the `display` on a model. |
| `#ambientOcclusion(boolean ambientOcclusion)`                              | Sets whether to use [ambient occlusion][ao] or not.                                                                                                                                                                                                                                                                                                     |
| `#guiLight(UnbakedModel.GuiLight light)`                                   | Sets the GUI light. May be `GuiLight.FRONT` or `GuiLight.SIDE`.                                                                                                                                                                                                                                                                                         |
| `#element(Consumer<ElementBuilder> action)`                                | Adds a new `ElementBuilder` (equivalent to adding a new [element][elements] to the model) that is configured via the consumer.                                                                                                                                                                                                 |                                                                                                                                                                                                                                                            |
| `#customLoader(Supplier customLoaderFactory, Consumer action)`            | Using the given factory, makes this model use a [custom loader][custommodelloader], and thus, a custom loader builder that is configured via the consumer. This changes the builder type, and as such may use different methods, depending on the loader's implementation. NeoForge provides a few custom loaders out of the box, see the linked article for more info (including datagen). |
| `#rootTransforms(Consumer<RootTransformsBuilder> action)`                  | Configures the transforms of the model to apply before item display and block state transformations via the consumer. |

:::tip
While elaborate and complex models can be created through datagen, it is recommended to instead use modeling software such as [Blockbench][blockbench] to create more complex models and then have the exported models be used, either directly or as parents for other models.
:::

### Creating the Model Instance

Now that we have a `ModelTemplate`, we can generate the model itself by calling one of the `ModelTemplate#create*` methods. Although each create method takes in different parameters, at their core, they all take in the `Identifier` representing the name of the file, a `TextureMapping` which maps a `TextureSlot` to some `Identifier` relative to the `textures` directory, and the model output as a `BiConsumer<Identifier, ModelInstance>`. Then, the method essentially creates the `JsonObject` used to generate the model, throwing an error if any duplicates are provided.

:::note
Calling the base `create` method does not apply the stored suffix. Only `create*` methods that takes in the block or item do so.
:::

```java
// Given some BiConsumer<Identifier, ModelInstance> modelOutput
// Assume there is a DeferredBlock<Block> EXAMPLE_BLOCK
EXAMPLE_TEMPLATE.create(
    // Creates the model at 'assets/minecraft/models/block/example_block_example.json'
    EXAMPLE_BLOCK.get(),
    // Define textures in slots
    new TextureMapping()
        // "particle": "examplemod:item/example_block"
        .put(TextureSlot.PARTICLE, TextureMapping.getBlockTexture(EXAMPLE_BLOCK.get()))
        // "base": "examplemod:item/example_block_base"
        .put(TextureSlot.BASE, TextureMapping.getBlockTexture(EXAMPLE_BLOCK.get(), "_base")),
    // The consumer of the generated model json
    modelOutput
);
```

Sometimes, generated models use similar model templates and naming patterns for their textures (e.g., the texture for a regular block is just the name of the block). In these cases, a `TexturedModel.Provider` can be created to help remove any redundancy. The provider is effectively a functional interface that takes in some `Block` and returns a `TexturedModel` (a `ModelTemplate`/`TextureMapping` pair) to generate the model. The interface is constructed via `TexturedModel#createDefault`, which takes a function to map a `Block` to its `TextureMapping` along with the `ModelTemplate` to use. Then the model can be generated by calling `TexturedModel.Provider#create` with the `Block` to generate for.

```java
public static final TexturedModel.Provider EXAMPLE_TEMPLATE_PROVIDER = TexturedModel.createDefault(
    // Block to texture mapping
    block -> new TextureMapping()
        .put(TextureSlot.PARTICLE, TextureMapping.getBlockTexture(block))
        .put(TextureSlot.BASE, TextureMapping.getBlockTexture(block, "_base")),
    // The template to generate from
    EXAMPLE_TEMPLATE
);

// Given some BiConsumer<Identifier, ModelInstance> modelOutput
// Assume there is a DeferredBlock<Block> EXAMPLE_BLOCK
EXAMPLE_TEMPLATE_PROVIDER.create(
    // Creates the model at 'assets/minecraft/models/block/example_block_example.json'
    EXAMPLE_BLOCK.get(),
    // The consumer of the generated model json
    modelOutput
);
```

## `ModelProvider`

Both block and item model datagen utilize generators provided by `registerModels`, named `BlockModelGenerators` and `ItemModelGenerators`, respectively. Each generator generates both the model JSON along with any additional required files (blockstate, client items). Each generator contains various helper methods which batches the construction of all the files into a single, easy-to-use method, such as `ItemModelGenerators#generateFlatItem` with `ModelTemplates#FLAT_ITEM` to create a basic `item/generated` model or `BlockModelGenerators#createTrivialCube` for a basic `block/cube_all` model.

```java
public class ExampleModelProvider extends ModelProvider {

    public ExampleModelProvider(PackOutput output) {
        // Replace "examplemod" with your own mod id.
        super(output, "examplemod");
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        // Generate models and associated files here
    }
}
```

And like all data providers, don't forget to register your provider to the event:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createProvider(ExampleModelProvider::new);
}
```

### Block Model Datagen

Now, to actually generate blockstate and block model files, you can either call one of the many public methods in `BlockModelGenerators` within `ModelProvider#registerModels`, or pass in the generated files yourself to the `blockStateOutput` for blockstate files, `itemModelOutput` for non-trivial client items, and `modelOutput` for the model JSONs.

:::note
If you have an associated `BlockItem` registered for your block with no generated client item, the `ModelProvider` will automatically generate a client item using the default block model location `assets/<namespace>/models/block/<path>.json` as its model.
:::

```java
public class ExampleModelProvider extends ModelProvider {

    public ExampleModelProvider(PackOutput output) {
        // Replace "examplemod" with your own mod id.
        super(output, "examplemod");
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        // Placeholders, their usages should be replaced with real values. See above for how to use the model builder,
        // and below for the helpers the model builder offers.
        Block block = MyBlocksClass.EXAMPLE_BLOCK.get();

        // Create a simple block model with the same texture on each side.
        // The texture must be located at assets/<namespace>/textures/block/<path>.png, where
        // <namespace> and <path> are the block's registry name's namespace and path, respectively.
        // Used by the majority of (full) blocks, such as planks, cobblestone or bricks.
        blockModels.createTrivialCube(block);

        // Overload that accepts a `TexturedModel.Provider` to use.
        blockModels.createTrivialBlock(block, EXAMPLE_TEMPLATE_PROVIDER);

        // Block items have a model generated automatically
        // But let's assume you want to generate a different item, such as a flat item
        blockModels.registerSimpleFlatItemModel(block);

        // Adds a log block model. Requires two textures at assets/<namespace>/textures/block/<path>.png and
        // assets/<namespace>/textures/block/<path>_top.png, referencing the side and top texture, respectively.
        // Note that the block input here is limited to RotatedPillarBlock, which is the class vanilla logs use.
        blockModels.woodProvider(block).log(block);
        
        // Like WoodProvider#logWithHorizontal. Used by quartz pillars and similar blocks.
        blockModels.createRotatedPillarWithHorizontalVariant(block, TexturedModel.COLUMN_ALT, TexturedModel.COLUMN_HORIZONTAL_ALT);

        // Using the `ExtendedModelTemplate` to specify the render type to use.
        blockModels.createRotatedPillarWithHorizontalVariant(block,
            TexturedModel.COLUMN_ALT.updateTemplate(template ->
                template.extend().renderType("minecraft:cutout").build()
            ),
            TexturedModel.COLUMN_HORIZONTAL_ALT.updateTemplate(template ->
                template.extend().renderType(this.mcLocation("cutout_mipped")).build()
            )
        );

        // Specifies a horizontally-rotatable block model with a side texture, a front texture, and a top texture.
        // The bottom will use the side texture as well. If you don't need the front or top texture,
        // just pass in the side texture twice. Used by e.g. furnaces and similar blocks.
        blockModels.createHorizontallyRotatedBlock(
            block,
            TexturedModel.Provider.ORIENTABLE_ONLY_TOP.updateTexture(mapping ->
                mapping.put(TextureSlot.SIDE, this.modLocation("block/example_texture_side"))
                .put(TextureSlot.FRONT, this.modLocation("block/example_texture_front"))
                .put(TextureSlot.TOP, this.modLocation("block/example_texture_top"))
            )
        );

        // Specifies a horizontally-rotatable block model that is attached to a face, e.g. for buttons.
        // Accounts for placing the block on the ground and on the ceiling, and rotates them accordingly.
        blockModels.familyWithExistingFullBlock(block).button(block);

        // Create a model to use for blockstatefiles
        Identifier modelLoc = TexturedModel.CUBE.create(block, blockModels.modelOutput);

        // Create a common variant to transform
        Variant variant = new Variant(modelLoc);

        // Basic single variant model
        blockModels.blockStateOutput.accept(
            MultiVariantGenerator.dispatch(
                block,
                new MultiVariant(
                    WeightedList.of(
                        new Weighted<>(
                            // Set model
                            variant
                                // Set rotations around the x and y axes
                                .with(VariantMutator.X_ROT.withValue(Quadrant.R90))
                                .with(VariantMutator.Y_ROT.withValue(Quadrant.R180))
                                // Set a uvlock
                                .with(VariantMutator.UV_LOCK.withValue(true)),
                            // Set a weight
                            5
                        )
                    )
                )
            )
        );

        // Add one or multiple models based on the block state properties
        blockModels.blockStateOutput.accept(
            MultiVariantGenerator.dispatch(
                block,
                // Create the basic multi-variant
                BlockModelGenerators.variant(variant)
            ).with(
                // Apply a property dispatch
                // Will mutate the variant based on the provided mutators
                PropertyDispatch.modify(BlockStateProperties.AXIS)
                    .select(Direction.Axis.Y, BlockModelGenerators.NOP)
                    .select(Direction.Axis.Z, BlockModelGenerators.X_ROT_90)
                    .select(Direction.Axis.X, BlockModelGenerators.X_ROT_90.then(BlockModelGenerators.Y_ROT_90))
            )
        );

        // Generate a multipart
        blockModels.blockStateOutput.accept(
            MultiPartGenerator.multiPart(block)
                // Provide the base model
                .with(BlockModelGenerators.variant(variant))
                // Add conditions for variant to appear
                .with(
                    // Add conditions to apply
                    new CombinedCondition(
                        CombinedCondition.Operation.OR,
                        List.of(
                            // Where at least one of the conditions are true
                            BlockModelGenerators.condition().term(BlockStateProperties.FACING, Direction.NORTH, Direction.SOUTH)
                            // Can nest as many conditions or groups as necessary
                            new CombinedCondition(
                                CombinedCondition.Operation.AND,
                                List.of(
                                    BlockModelGenerators.condition().term(BlockStateProperties.FACING, Direction.NORTH)
                                )
                            )
                        )
                    ),
                    // Supply variant to mutate
                    BlockModelGenerators.variant(variant)
                )
        );
    }
}
```

## Item Model Datagen

Generating item models is considerably simpler, which is mainly due to all of the helper methods for within `ItemModelGenerators` and `ItemModelUtils` for property information. Similar to above, you can either call one of the many public methods in `ItemModelGenerators` within `ModelProvider#registerModels`, or pass in the generated files yourself to the `itemModelOutput` for non-trivial client items and `modelOutput` for the model JSONs.

```java
public class ExampleModelProvider extends ModelProvider {

    public ExampleModelProvider(PackOutput output) {
        // Replace "examplemod" with your own mod id.
        super(output, "examplemod");
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        // The most common item
        // item/generated with the layer0 texture as the item name
        itemModels.generateFlatItem(MyItemsClass.EXAMPLE_ITEM.get(), ModelTemplates.FLAT_ITEM);

        // A bow-like item
        ItemModel.Unbaked bow = ItemModelUtils.plainModel(ModelLocationUtils.getModelLocation(MyItemsClass.EXAMPLE_ITEM.get()));
        ItemModel.Unbaked pullingBow0 = ItemModelUtils.plainModel(this.createFlatItemModel(MyItemsClass.EXAMPLE_ITEM.get(), "_pulling_0", ModelTemplates.BOW));
        ItemModel.Unbaked pullingBow1 = ItemModelUtils.plainModel(this.createFlatItemModel(MyItemsClass.EXAMPLE_ITEM.get(), "_pulling_1", ModelTemplates.BOW));
        ItemModel.Unbaked pullingBow2 = ItemModelUtils.plainModel(this.createFlatItemModel(MyItemsClass.EXAMPLE_ITEM.get(), "_pulling_2", ModelTemplates.BOW));
        this.itemModelOutput.accept(
            MyItemsClass.EXAMPLE_ITEM.get(),
            // Conditional model for item
            ItemModelUtils.conditional(
                // Checks if item is being used
                ItemModelUtils.isUsingItem(),
                // When true, select model based on use duration
                ItemModelUtils.rangeSelect(
                    new UseDuration(false),
                    // Scalar to apply to the thresholds
                    0.05F,
                    pullingBow0,
                    // Threshold when 0.65
                    ItemModelUtils.override(pullingBow1, 0.65F),
                    // Threshold when 0.9
                    ItemModelUtils.override(pullingBow2, 0.9F)
                ),
                // When false, use the base bow model
                bow
            ),
            // Some settings to use during the rendering process
            new ClientItem.Properties(
                // When false, disables the animation where the item is raised
                // up towards its normal position on item swap
                false,
                // When true, allows the model to render outside its defined
                // slot bounds (defined in GuiItemRenderState#bounds) in a GUI
                // instead of being scissored
                false,
                // Applies the scalar to the height of the hand when swapping
                1.0F
            )
        );
    }
}
```

[ao]: https://en.wikipedia.org/wiki/Ambient_occlusion
[blockbench]: https://www.blockbench.net
[citems]: items.md
[custommodelloader]: modelloaders.md#datagen
[datagen]: ../../index.md#data-generation
[elements]: index.md#elements

## resources/client/models/index

# Models

Models are JSON files that determine the visual shape and texture(s) of a block or item. A model consists of cuboid elements, each with their own size, that then get assigned a texture to each face.

Items use the associated model(s) defined by its [client item][citems] while blocks use the associated model in the [blockstate file][bsfile]. These locations are relative to the `models` directory, so a model referenced by the name `examplemod:item/example_model` would be defined by the JSON at `assets/examplemod/models/item/example_model.json`.
 
## Specification

_See also: [Model][mcwikimodel] on the [Minecraft Wiki][mcwiki]_

A model is a JSON file with the following optional properties in the root tag:

- `loader`: NeoForge-added. Sets a custom model loader. See [Model Loaders][custommodelloader] for more information.
- `parent`: Sets a parent model, in the form of a [resource location][rl] relative to the `models` folder. All parent properties will be applied and then overridden by the properties set in the declaring model. Common parents include:
    - `minecraft:block/block`: The common parent of all block models.
    - `minecraft:block/cube`: Parent of all models that use a 1x1x1 cube model.
    - `minecraft:block/cube_all`: Variant of the cube model that uses the same texture on all six sides, for example cobblestone or planks.
    - `minecraft:block/cube_bottom_top`: Variant of the cube model that uses the same texture on all four horizontal sides, and separate textures on the top and the bottom. Common examples include sandstone or chiseled quartz.
    - `minecraft:block/cube_column`: Variant of the cube model that has a side texture and a bottom and top texture. Examples include wooden logs, as well as quartz and purpur pillars.
    - `minecraft:block/cross`: Model that uses two planes with the same texture, one rotated 45° clockwise and the other rotated 45° counter-clockwise, forming an X when viewed from above (hence the name). Examples include most plants, e.g. grass, saplings and flowers.
    - `minecraft:item/generated`: Parent for classic 2D flat item models. Used by most items in the game. Ignores an `elements` block since its quads are generated from the textures.
    - `minecraft:item/handheld`: Parent for 2D flat item models that appear to be actually held by the player. Used predominantly by tools. Submodel of `item/generated`, which causes it to ignore the `elements` block as well.
    - Block items commonly (but not always) use their corresponding block models for their [item model][itemmodels]. For example, the cobblestone client item uses the `minecraft:block/cobblestone` model.
- `ambientocclusion`: Whether to enable [ambient occlusion][ao] or not. Only effective on block models. Defaults to `true`. If your custom block model has weird shading, try setting this to `false`.
- `gui_light`: Can be `"front"` or `"side"`. If `"front"`, light will come from the front, useful for flat 2D models. If `"side"`, light will come from the side, useful for 3D models (especially block models). Defaults to `"side"`. Only effective on item models.
- `textures`: A sub-object that maps names (known as material variables) to `Material`s. Material variables can then be used in [elements]. They can also be specified in elements, but left unspecified in order for child models to specify them.
    - `sprite`: The [location of the texture][textures].
    - `force_translucent`: When `true`, forces the face this texture is applied to render in the 'translucent' layer. When `false`:
        - If the texture only has opaque pixels (alpha `255`), the face will render in the 'solid' layer
        - If the texture has pixels that are either opaque or completely transparent (alpha either `0` or `255`), the face will render in the 'cutout' layer
        - Otherwise, the face will render in the 'translucent' layer

:::tip
Block models should additionally specify a `particle` texture. This texture is used when falling on, running across, or breaking the block. 

Item models can also use layer textures, named `layer0`, `layer1`, etc., where layers with a higher index are rendered above those with a lower index (e.g. `layer1` would be rendered above `layer0`). Only works if the parent is `item/generated`, and only works for up to 5 layers (`layer0` through `layer4`).
:::

- `elements`: A list of cuboid [elements].
- `display`: A sub-object that holds the different display options for different [perspectives], see linked article for possible keys. Only effective on item models, but often specified in block models so that item models can inherit the display options. Every perspective is an optional sub-object that may contain the following options, which are applied in that order:
    - `translation`: The translation of the model, specified as `[x, y, z]`.
    - `rotation`: The rotation of the model, specified as `[x, y, z]`.
    - `scale`: The scale of the model, specified as `[x, y, z]`.
    - `right_rotation`: NeoForge-added. A second rotation that is applied after scaling, specified as `[x, y, z]`.
- `transform`: See [Root Transforms][roottransforms].

:::tip
If you're having trouble finding out how exactly to specify something, have a look at a vanilla model that does something similar.
:::

### Elements

An element is a JSON representation of a cuboid object. It has the following properties:

- `from`: The coordinate of the start corner of the cuboid, specified as `[x, y, z]`. Specified in 1/16 block units. For example, `[0, 0, 0]` would be the "bottom left" corner, `[8, 8, 8]` would be the center, and `[16, 16, 16]` would be the "top right" corner of the block.
- `to`: The coordinate of the end corner of the cuboid, specified as `[x, y, z]`. Like `from`, this is specified in 1/16 block units.

:::tip
Values in `from` and `to` are limited by Minecraft to the range `[-16, 32]`. However, it is highly discouraged to exceed `[0, 16]`, as that will lead to lighting and/or culling issues.
:::

- `neoforge_data`: See [Extra Face Data][extrafacedata].
- `faces`: An object containing data for of up to 6 faces, named `north`, `south`, `east`, `west`, `up` and `down`, respectively. Every face has the following data:
    - `uv`: The uv of the face, specified as `[u1, v1, u2, v2]`, where `u1, v1` is the top left uv coordinates and `u2, v2` is the bottom right uv coordinates.
    - `texture`: The texture to use for the face. Must be a texture variable prefixed with a `#`. For example, if your model had a texture named `wood`, you would use `#wood` to reference that texture. Technically optional, will use the missing texture if absent.
    - `rotation`: Optional. Rotates the texture clockwise by 90, 180 or 270 degrees.
    - `cullface`: Optional. Tells the render engine to skip rendering the face when there is a full block touching it in the specified direction. The direction can be `north`, `south`, `east`, `west`, `up` or `down`.
    - `tintindex`: Optional. Specifies a tint index that may be used by a color handler, see [Tinting][tinting] for more information. Defaults to -1, which means no tinting.
    - `neoforge_data`: See [Extra Face Data][extrafacedata].

Additionally, it can specify the following optional properties:

- `shade`: Only for block models. Optional. Whether the faces of this element should have direction-dependent shading on it or not. Defaults to true.
- `rotation`: A rotation of the object, specified as a sub object containing the following data:
    - `angle`: The rotation angle, in degrees.
    - `axis`: The axis to rotate around. It is currently not possible to rotate an object around more than one axis.
    - `origin`: Optional. The origin point to rotate around, specified as `[x, y, z]`. Note that these are absolute values, i.e. they are not relative to the cube's position. If unspecified, will use `[0, 0, 0]`.

#### Extra Face Data

Extra face data (`neoforge_data`) can be applied to both an element and a single face of an element. It is optional in all contexts where it is available. If both element-level and face-level extra face data is specified, the face-level data will override the element-level data. Extra data can specify the following data:

- `color`: Tints the face with the given color. Must be an ARGB value. Can be specified as a string or as a decimal integer (JSON does not support hex literals). Defaults to `0xFFFFFFFF`. This can be used as a replacement for tinting if the color values are constant.
- `block_light`: Overrides the block light value used for this face. Defaults to 0.
- `sky_light`: Overrides the sky light value used for this face. Defaults to 0.
- `ambient_occlusion`: Disables or enables ambient occlusion for this face. Defaults to the value set in the model.

### Root Transforms

Adding the `transform` property at the top level of a model tells the loader that a transformation to all geometry should be applied right before the rotations in a [blockstate file][bsfile] (for block models) or the transformations in a `display` block (for item models) are applied. This is added by NeoForge.

The root transforms can be specified in two ways. The first way would be as a single property named `matrix` containing a transformation 3x4 matrix (row major order, last row is omitted) in the form of a nested JSON array. The matrix is the composition of the translation, left rotation, scale, right rotation and the transformation origin in that order. An example would look like this:

```json5
{
    // ...
    "transform": {
        "matrix": [
            [0, 0, 0, 0],
            [0, 0, 0, 0],
            [0, 0, 0, 0]
        ]
    }
}
```

The second way is to specify a JSON object containing any combination of the following entries, applied in that order:

- `translation`: The relative translation. Specified as a three-dimensional vector (`[x, y, z]`) and defaults to `[0, 0, 0]` if absent.
- `rotation` or `left_rotation`: Rotation around the translated origin to be applied before scaling. Defaults to no rotation. Specified in one of the following ways:
    - A JSON object with a single axis to rotation mapping, e.g. `{"x": 90}`
    - An array of JSON objects with a single axis to rotation mapping each, applied in the order they are specified in, e.g. `[{"x": 90}, {"y": 45}, {"x": -22.5}]`
    - An array with three values that each specify the rotation around each axis, e.g. `[90, 45, -22.5]`
    - An array with four values directly specifying a quaternion, e.g. `[0.38268346, 0, 0, 0.9238795]` (= 45 degrees around the X axis)
- `scale`: The scale relative to the translated origin. Specified as a three-dimensional vector (`[x, y, z]`) and defaults to `[1, 1, 1]` if absent.
- `post_rotation` or `right_rotation`: Rotation around the translated origin to be applied after scaling. Defaults to no rotation. Specified the same as `rotation`.
- `origin`: Origin point used for rotation and scaling. The transformation is also moved here as a final step. Specified either as a three-dimensional vector (`[x, y, z]`) or using one of the three builtin values `"corner"` (= `[0, 0, 0]`), `"center"` (= `[0.5, 0.5, 0.5]`) or `"opposing-corner"` (= `[1, 1, 1]`, default).

## Blockstate Files

_See also: [Blockstate files][mcwikiblockstate] on the [Minecraft Wiki][mcwiki]_

Blockstate files are used by the game to assign different models to different [blockstates]. There must be exactly one blockstate file per block registered to the game. Specifying block models for blockstates works in three mutually exclusive ways: via variants, multipart, or the NeoForge added definition type.

Inside a `variants` block, there is an element for each blockstate. This is the predominant way of associating blockstates with models, used by the vast majority of blocks.
- The key is the string representation of the blockstate without the block name, so for example `"type=top,waterlogged=false"` for a non-waterlogged top slab, or `""` for a block with no properties. It is worth noting that unused properties may be omitted. For example, if the `waterlogged` property has no influence on the model chosen, two objects `type=top,waterlogged=false` and `type=top,waterlogged=true` may be collapsed into one `type=top` object. This also means that an empty string is valid for every block.
- The value is either a single model object or an array of model objects. If an array of model objects is used, a model will be randomly chosen from it. A model object consists of the following data:
    - `type`: NeoForge-added. Sets a custom block state model loader. See [Block State Model Loaders][bsmmodelloader] for more information.
    - `model`: A path to a model file location, relative to the namespace's `models` folder, for example `minecraft:block/cobblestone`.
    - `x` and `y`: Rotation of the model on the x-axis/y-axis. Limited to steps of 90 degrees. Optional each, defaults to 0.
    - `uvlock`: Whether to lock the UVs of the model when rotating or not. Optional, defaults to false.
    - `weight`: Only useful with arrays of model objects. Gives the object a weight, used when choosing a random model object. Optional, defaults to 1.

In contrast, inside a `multipart` block, elements are combined depending on the properties of the blockstate. This method is mainly used by fences and walls, who enable the four directional parts based on boolean properties. A multipart element consists of two parts: a `when` block and an `apply` block.

- The `when` block specifies either a string representation of a blockstate or a list of properties that must be met for the element to apply. The lists can either be named `"OR"` or `"AND"`, performing the respective logical operation on its contents. Both single blockstate and list values can additionally specify multiple actual values by separating them with `|` (for example `facing=east|facing=west`).
- The `apply` block specifies the model object or an array of model objects to use. This works exactly like with a `variants` block.

Finally, a `neoforge:definition_type` can specify a custom model loader to register the block state file See [Block State Definition Loaders][bsdmodelloader] for more information.

## Client Items

[Client items][citems] are used by the game to assign a model or multiple models to the states of the `ItemStack`. While there are some item-specific fields in the model JSON, client items consume models to render based on context, so most of their information has been moved to their own separate [section][citems].

## Tinting

Some blocks, such as grass or leaves, change their texture color based on their location and/or properties. [Model elements][elements] can specify a tint index on their faces, which will allow a color handler to handle the respective faces. The code side of things works through three events, one for block tint sources, one for block tints based on biome (used in conjunction with the block tint sources), and one for item tint sources. So let's have a look at a block tint source first:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerBlockColorHandlers(RegisterColorHandlersEvent.BlockTintSources event) {
    // Parameters are the block's state, the level the block is in, the block's position, and the tint index.
    // The level and position may be null.
    event.register(
        // A list of tint sources to apply to the block. The 'tintindex' defined in
        // the model indexes into the list.
        List.of(
            // For 'tintindex: 0'.
            // Takes in the block's state.
            state -> {
                // Replace with your own calculation. See the BlockColors class for vanilla references.
                // Colors are in ARGB format.
                return 0xFFFFFFFF;
            },
            // For 'tintindex: 1',
            new BlockTintSource() {

                @Override
                public int color(BlockState state) {
                    // The default tint to apply.
                    return 0xFFFFFFFF;
                }

                @Override
                public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                    // The tint to apply when the block is in the world.
                    // Defaults to `color` if not overridden.
                    return 0xFFFFFFFF;
                }

                @Override
                public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                    // The tint to apply when a `TerrainParticle` is spawned.
                    // Defaults to `colorInWorld` if not overridden.
                    return 0xFFFFFFFF;
                }
            }
        ),
        // A varargs of blocks to apply the tinting to
        EXAMPLE_BLOCK.get(), ...
    );
}
```

Here is an example for a color resolver:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerColorResolvers(RegisterColorHandlersEvent.ColorResolvers event) {
    // Parameters are the current biome, the block's X position, and the block's Z position.
    event.register((biome, x, z) -> {
        // Replace with your own calculation. See the BiomeColors class for vanilla references.
        // Colors are in ARGB format.
        return 0xFFFFFFFF;
    });
}
```

For item tinting, please see the [relevant section in the client items article][itemtints].

## Registering Standalone Models

Models that are not associated with a block or item in some way, but are still required in other contexts (e.g. [block entity renderers][ber]), can be registered through `ModelEvent.RegisterStandalone`:

```java
// This can be any type as long as it can be obtained from the ResolvedModel and the ModelBaker
// The generic type should be whatever is the generic type of the UnbakedStandaloneModel<T>
public static final StandaloneModelKey<QuadCollection> EXAMPLE_KEY = new StandaloneModelKey<>(
    new ModelDebugName() {
        @Override
        public String debugName() {
            // A name for the standalone model
            // Can be any string, but it should contain the mod id
            return "examplemod: Example Model";
        }
    }
);

@SubscribeEvent // on the mod event bus only on the physical client
public static void registerAdditional(ModelEvent.RegisterStandalone event) {
    event.register(
        // The model to get
        EXAMPLE_KEY,
        // An UnbakedStandaloneModel<T> we care about, in this case one that returns a QuadCollection
        // Can use the static methods from SimpleUnbakedStandaloneModel<T> for simplicity
        SimpleUnbakedStandaloneModel.quadCollection(
            // The model id, relative to `assets/<namespace>/models/<path>.json`
            Identifier.fromNamespaceAndPath("examplemod", "block/example_unused_model")
        )
    );
}
```

[ao]: https://en.wikipedia.org/wiki/Ambient_occlusion
[ber]: ../../../blockentities/ber.md
[bsfile]: #blockstate-files
[bsdmodelloader]: modelloaders.md#block-state-definition-loaders
[bsmmodelloader]: modelloaders.md#block-state-model-loaders
[custommodelloader]: modelloaders.md#model-loaders
[elements]: #elements
[event]: ../../../concepts/events.md
[extrafacedata]: #extra-face-data
[citems]: items.md
[itemmodel]: items.md#a-basic-model
[itemtints]: items.md#tinting
[mcwiki]: https://minecraft.wiki
[mcwikiblockstate]: https://minecraft.wiki/w/Tutorials/Models#Block_states
[mcwikimodel]: https://minecraft.wiki/w/Model
[mipmapping]: https://en.wikipedia.org/wiki/Mipmap
[modbus]: ../../../concepts/events.md#event-buses
[perspectives]: modelsystem.md#perspectives
[rendertype]: #render-types
[roottransforms]: #root-transforms
[rl]: ../../../misc/identifier.md
[textures]: ../textures.md
[tinting]: #tinting

## resources/client/models/items

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Client Items

Client Items are the in-code representation of how an `ItemStack` should be submitted for rendering within the game, specifying what models to use given what state. The client items are located within the `items` subdirectory within the [`assets` folder][assets], specified by the relative location within `DataComponents#ITEM_MODEL`. By default, this is the registry name of the object (e.g. `minecraft:apple` would be located at `assets/minecraft/items/apple.json` by default).

The client items are stored within the `ModelManager`, which can be accessed through `Minecraft.getInstance().modelManager`. Then, you can call `ModelManager#getItemModel` or `getItemProperties` to get the client item information by its [`Identifier`][rl].

:::warning
These are not to be confused with the actual [models that are baked and actually rendered][models] in-game.
:::

## Overview

The JSON of a client item can be broken into two parts: the model, defined by `model`; and the properties, defined by `properties`. The `model` is responsible for defining what model JSONs to use when submitting the `ItemStack` for rendering in a given context. The `properties`, on the other hand, is responsible for settings used by the renderer.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    // Defines the model to submit for rendering
    "model": {
        "type": "minecraft:model",
        // Points to a model JSON relative to the 'models' directory
        // Located at 'assets/examplemod/models/item/example_item.json'
        "model": "examplemod:item/example_item"
    },
    // Defines some settings to use during the rendering process
    "properties": {
        // When false, disables the animation where the item is raised
        // up towards its normal position on item swap
        "hand_animation_on_swap": false,
        // When true, allows the model to render outside its defined
        // slot bounds (defined in GuiItemRenderState#bounds) in a GUI
        // instead of being scissored
        "oversized_in_gui": false,
        // Applies the scalar to the height of the hand when swapping
        "swap_animation_scale": 1.0
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.register(
        EXAMPLE_ITEM.get(),
        // Defines the model to submit for rendering
        new CuboidItemModelWrapper.Unbaked(
            // Points to a model JSON relative to the 'models' directory
            // Located at 'assets/examplemod/models/item/example_item.json'
            ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
            Optional.empty(),
            Collections.emptyList()
        ),
        // Defines some settings to use during the rendering process
        new ClientItem.Properties(
            // When false, disables the animation where the item is raised
            // up towards its normal position on item swap
            false,
            // When true, allows the model to render outside its defined
            // slot bounds (defined in GuiItemRenderState#bounds) in a GUI
            // instead of being scissored
            false,
            // Applies the scalar to the height of the hand when swapping
            1.0F
        )
    );
}
```

</TabItem>
</Tabs>

More information about how item models are submitted for rendering can be found [below][itemmodel].

## A Basic Model

The `type` field within `model` determines how to choose the model being submitted to render for the item. The simplest type is handled by `minecraft:model` (or `CuboidItemModelWrapper`), which functionally defines the model JSON being submitted to render, relative to the `models` directory (e.g. `assets/<namespace>/models/<path>.json`).

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:model",
        // Points to a model JSON relative to the 'models' directory
        // Located at 'assets/examplemod/models/item/example_item.json'
        "model": "examplemod:item/example_item"
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new CuboidItemModelWrapper.Unbaked(
            // Points to a model JSON relative to the 'models' directory
            // Located at 'assets/examplemod/models/item/example_item.json'
            ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
            Optional.empty(),
            Collections.emptyList()
        )
    );
}
```

</TabItem>
</Tabs>

### Local Transforms

Most client item models can specify a `Transformation` for the item model, similar to model JSONs. These `Transformation`s are applied after the model JSON transform for the associated display context. This is set through the `minecraft:model` type `transformation` field.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:model",
        // Points to 'assets/examplemod/models/item/example_item.json'
        "model": "examplemod:item/example_item",
        // The transformations to apply after the model JSON transforms.
        "transformation": {
            // The translation of the client item, specified as `[x, y, z]`.
            "translation": [
                0.5,
                0.0,
                0.5
            ],
            // The initial rotation of the client item, specified as:
            // - `[x, y, z, w]`
            // - { angle, [x, y, z] rotation axis }
            "left_rotation": [
                1.0,
                0.0,
                0.0,
                0.0
            ],
            // The scale of the client item, specified as `[x, y, z]`.
            "scale": [
                1.0,
                1.0,
                1.0
            ],
            // The rotation of the client item after scaling, specified as:
            // - `[x, y, z, w]`
            // - { angle, [x, y, z] rotation axis }
            "right_rotation": {
                "angle": 0,
                "axis": [
                    0.0,
                    0.0,
                    0.0
                ]
            }
        }
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new CuboidItemModelWrapper.Unbaked(
            // Points to 'assets/examplemod/models/item/example_item.json'
            ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
            // The transformations to apply after the model JSON transforms.
            Optional.of(new Transformation(
                // The translation of the client item.
                new Vector3f(0.5f, 0f, 0.5f),
                // The initial rotation of the client item.
                new Quaternionf(1f, 0f, 0f, 0f),
                // The scale of the client item.
                new Vector3f(1f, 1f, 1f),
                // The rotation of the client item after scaling.
                new Quaternionf(new AxisAngle4f(0f, 0f, 0f, 0f))
            )),
            Collections.emptyList()
        )
    );
}
```

</TabItem>
</Tabs>

### Tinting

Like most models, client items can change the color of the specified texture based on the properties of the stack. As such, the `minecraft:model` type has the `tints` field to define the opaque colors to apply. These are known as `ItemTintSource`s, which are defined in `ItemTintSources`. They also have a `type` field to define which source to use. The `tintindex` they are applied to is specified by their index within the list.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:model",
        // Points to 'assets/examplemod/models/item/example_item.json'
        "model": "examplemod:item/example_item",
        // A list of tints to apply
        "tints": [
            {
                // For when tintindex: 0
                "type": "minecraft:constant",
                // 0x00FF00 (or pure green)
                "value": 65280
            },
            {
                // For when tintindex: 1
                "type": "minecraft:dye",
                // 0x0000FF (or pure blue)
                // Only is called if `DataComponents#DYED_COLOR` is not set
                "default": 255
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new CuboidItemModelWrapper.Unbaked(
            // Points to 'assets/examplemod/models/item/example_item.json'
            ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
            Optional.empty(),
            // A list of tints to apply
            List.of(
                // For when tintindex: 0
                new Constant(
                    // Pure green
                    0x00FF00
                ),
                // For when tintindex: 1
                new Dye(
                    // Pure blue
                    // Only is called if `DataComponents#DYED_COLOR` is not set
                    0x0000FF
                )
            )
        )
    );
}
```

</TabItem>
</Tabs>

Creating your own `ItemTintSource` is similar to any other codec-based registry object. You make a class that implements `ItemTintSource`, create a `MapCodec` to encode and decode the object, and register the codec to its registry via `RegisterColorHandlersEvent.ItemTintSources` on the [mod event bus][modbus]. The `ItemTintSource` only contains one method `calculate`, which takes in the current `ItemStack`, the level the stack is in, and the entity holding the stack to return an opaque color in ARGB format, where the top 8 bits are 0xFF.

```java
public record DamageBar(int defaultColor) implements ItemTintSource {

    // The map codec to register
    public static final MapCodec<DamageBar> MAP_CODEC = ExtraCodecs.RGB_COLOR_CODEC.fieldOf("default")
        .xmap(DamageBar::new, DamageBar::defaultColor);

    public DamageBar(int defaultColor) {
        // Make sure the passed in color is opaque
        this.defaultColor = ARGB.opaque(defaultColor);
    }

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity) {
        return stack.isDamaged() ? ARGB.opaque(stack.getBarColor()) : defaultColor;
    }

    @Override
    public MapCodec<DamageBar> type() {
        return MAP_CODEC;
    }
}

// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerItemTintSources(RegisterColorHandlersEvent.ItemTintSources event) {
    event.register(
        // The name to reference as the type
        Identifier.fromNamespaceAndPath("examplemod", "damage_bar"),
        // The map codec
        DamageBar.MAP_CODEC
    )
}
```

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:model",
        // Points to 'assets/examplemod/models/item/example_item.json'
        "model": "examplemod:item/example_item",
        // A list of tints to apply
        "tints": [
            {
                // For when tintindex: 0
                "type": "examplemod:damage_bar",
                // 0x00FF00 (or pure green)
                "default": 65280
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new CuboidItemModelWrapper.Unbaked(
            // Points to 'assets/examplemod/models/item/example_item.json'
            ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
            Optional.empty(),
            // A list of tints to apply
            List.of(
                // For when tintindex: 0
                new DamageBar(
                    // Pure green
                    0x00FF00
                )
            )
        )
    );
}
```

</TabItem>
</Tabs>

## Composite Models

Sometimes, you may want to register multiple models for a single item. While this can be done directly with the [composite model loader][composite], for item models, there is a custom `minecraft:composite` type which takes a list of models to submit for rendering.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:composite",

        // The models to submit for rendering
        // Will be drawn in the order they appear in the list
        "models": [
            {
                "type": "minecraft:model",
                // Points to 'assets/examplemod/models/item/example_item_1.json'
                "model": "examplemod:item/example_item_1"
            },
            {
                "type": "minecraft:model",
                // Points to 'assets/examplemod/models/item/example_item_2.json'
                "model": "examplemod:item/example_item_2"
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new CompositeModel.Unbaked(
            // The models to submit for rendering
            // Will be drawn in the order they appear in the list
            List.of(
                new CuboidItemModelWrapper.Unbaked(
                    // Points to 'assets/examplemod/models/item/example_item_1.json'
                    Identifier.fromNamespaceAndPath("examplemod", "item/example_item_1"),
                    Optional.empty(),
                    Collections.emptyList()
                ),
                new CuboidItemModelWrapper.Unbaked(
                    // Points to 'assets/examplemod/models/item/example_item_2.json'
                    Identifier.fromNamespaceAndPath("examplemod", "item/example_item_2"),
                    Optional.empty(),
                    Collections.emptyList()
                )
            )
        )
    );
}
```

</TabItem>
</Tabs>

## Property Models

Some items change their state depending on the data stored in their stack (e.g., pulling a bow, breaking an elytra, a clock when in a given dimension, etc.). To allow models to change based on state, item models can specify a property to keep track of and select a model based on that condition. There are three different types of property models: range dispatch, select, and conditional. Each of these act as a expression for some float, switch case, and boolean respectively.

### Range Dispatch Models

Range dispatch models have the type define some `RangeSelectItemModelProperty` to get some float to switch the model on. Each entry then has some threshold value which the float must be greater than to submit for rendering. The model chosen is the one with the closest threshold value that is not over the property value (e.g., if the property values is `4` with thresholds `3` and `5`, then the model associated with `3` will be drawn, and if the value was `6`, then the model associated with `5` would be drawn). The available `RangeSelectItemModelProperty`s to use can be found in `RangeSelectItemModelProperties`.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:range_dispatch",

        // The `RangeSelectItemModelProperty` to use
        "property": "minecraft:count",
        // A scalar to multiply to the computed property value
        // If count was 0.3 and scale was 0.2, then the threshold checked would be 0.3*0.2=0.06
        "scale": 1,
        "fallback": {
            // The fallback model to use if no threshold matches
            // Can be any unbaked model type
            "type": "minecraft:model",
            // Points to 'assets/examplemod/models/item/example_item.json'
            "model": "examplemod:item/example_item"
        },

        // Properties defined by `Count`
        // When true, normalizes the count using its max stack size
        "normalize": true,

        // Entries with threshold information
        "entries": [
            {
                // When the count is a third of its current max stack size
                "threshold": 0.33,
                "model": {
                    // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_1.json'
                    "model": "examplemod:item/example_item_1"
                }
            },
            {
                // When the count is two thirds of its current max stack size
                "threshold": 0.66,
                "model": {
                    // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_2.json'
                    "model": "examplemod:item/example_item_2"
                }
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new RangeSelectItemModel.Unbaked(
            new Count(
                // When true, normalizes the count using its max stack size
                true
            ),
            // A scalar to multiply to the computed property value
            // If count was 0.3 and scale was 0.2, then the threshold checked would be 0.3*0.2=0.06
            1,
            // Entries with threshold information
            List.of(
                new RangeSelectItemModel.Entry(
                    // When the count is a third of its current max stack size
                    0.33,
                    // Can be any unbaked model type
                    new CuboidItemModelWrapper.Unbaked(
                        // Points to 'assets/examplemod/models/item/example_item_1.json'
                        Identifier.fromNamespaceAndPath("examplemod", "item/example_item_1"),
                        Optional.empty(),
                        Collections.emptyList()
                    )
                ),
                new RangeSelectItemModel.Entry(
                    // When the count is two thirds of its current max stack size
                    0.66,
                    // Can be any unbaked model type
                    new CuboidItemModelWrapper.Unbaked(
                        // Points to 'assets/examplemod/models/item/example_item_2.json'
                        Identifier.fromNamespaceAndPath("examplemod", "item/example_item_2"),
                        Optional.empty(),
                        Collections.emptyList()
                    )
                )
            ),
            // The fallback model to use if no threshold matches
            Optional.of(
                new CuboidItemModelWrapper.Unbaked(
                    // Points to 'assets/examplemod/models/item/example_item.json'
                    ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
                    Optional.empty(),
                    Collections.emptyList()
                )
            )
        )
    );
}
```

</TabItem>
</Tabs>

Creating your own `RangeSelectItemModelProperty` is similar to any other codec-based registry object. You make a class that implements `RangeSelectItemModelProperty`, create a `MapCodec` to encode and decode the object, and register the codec to its registry via `RegisterRangeSelectItemModelPropertyEvent` on the [mod event bus][modbus]. The `RangeSelectItemModelProperty` only contains one method `get`, which takes in the current `ItemStack`, the level the stack is in, the entity holding the stack, and some seeded value to return an arbitrary float to be interpreted by the ranged dispatch model.

```java
public record AppliedEnchantments() implements RangeSelectItemModelProperty {

    public static final MapCodec<AppliedEnchantments> MAP_CODEC = MapCodec.unit(new AppliedEnchantments());

    @Override
    public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        return (float) stack.getTagEnchantments().size();
    }

    @Override
    public MapCodec<AppliedEnchantments> type() {
        return MAP_CODEC;
    }
}

// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerRangeProperties(RegisterRangeSelectItemModelPropertyEvent event) {
    event.register(
        // The name to reference as the type
        Identifier.fromNamespaceAndPath("examplemod", "applied_enchantments"),
        // The map codec
        AppliedEnchantments.MAP_CODEC
    )
}
```

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:range_dispatch",

        // The `RangeSelectItemModelProperty` to use
        "property": "examplemod:applied_enchantments",
        // A scalar to multiply to the computed property value
        // If count was 0.3 and scale was 0.2, then the threshold checked would be 0.3*0.2=0.06
        "scale": 0.5,
        "fallback": {
            // The fallback model to use if no threshold matches
            // Can be any unbaked model type
            "type": "minecraft:model",
            // Points to 'assets/examplemod/models/item/example_item.json'
            "model": "examplemod:item/example_item"
        },

        // Entries with threshold information
        "entries": [
            {
                // When there is at least one enchantment present
                // Since 1 * the scale 0.5 = 0.5
                "threshold": 0.5,
                "model": {
                    // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_1.json'
                    "model": "examplemod:item/example_item_1"
                }
            },
            {
                // When there are at least two enchantments present
                // Since 2 * the scale 0.5 = 1
                "threshold": 1,
                "model": {
                    // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_2.json'
                    "model": "examplemod:item/example_item_2"
                }
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new RangeSelectItemModel.Unbaked(
            new AppliedEnchantments(),
            // A scalar to multiply to the computed property value
            // If count was 0.3 and scale was 0.2, then the threshold checked would be 0.3*0.2=0.06
            0.5,
            // Entries with threshold information
            List.of(
                new RangeSelectItemModel.Entry(
                    // When there is at least one enchantment present
                    0.5,
                    // Can be any unbaked model type
                    new CuboidItemModelWrapper.Unbaked(
                        // Points to 'assets/examplemod/models/item/example_item_1.json'
                        Identifier.fromNamespaceAndPath("examplemod", "item/example_item_1"),
                        Optional.empty(),
                        Collections.emptyList()
                    )
                ),
                new RangeSelectItemModel.Entry(
                    // When there are at least two enchantments present
                    1,
                    // Can be any unbaked model type
                    new CuboidItemModelWrapper.Unbaked(
                        // Points to 'assets/examplemod/models/item/example_item_2.json'
                        Identifier.fromNamespaceAndPath("examplemod", "item/example_item_2"),
                        Optional.empty(),
                        Collections.emptyList()
                    )
                )
            ),
            // The fallback model to use if no threshold matches
            Optional.of(
                new CuboidItemModelWrapper.Unbaked(
                    // Points to 'assets/examplemod/models/item/example_item.json'
                    ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
                    Optional.empty(),
                    Collections.emptyList()
                )
            )
        )
    );
}
```

</TabItem>
</Tabs>

### Select Models

Select models are similar to range dispatch models, but they change switch based on some value defined by a `SelectItemModelProperty`, like a switch statement for an enum. The model chosen is the property which exactly matches the value in the switch case. The available `SelectItemModelProperty`s to use can be found in `SelectItemModelProperties`.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:select",

        // The `SelectItemModelProperty` to use
        "property": "minecraft:display_context",
        "fallback": {
            // The fallback model to use if no case matches
            // Can be any unbaked model type
            "type": "minecraft:model",
            "model": "examplemod:item/example_item"
        },

        // Switch cases based on Selectable Property
        "cases": [
            {
                // When the display context is `ItemDisplayContext#GUI`
                "when": "gui",
                "model": {
                    // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_1.json'
                    "model": "examplemod:item/example_item_1"
                }
            },
            {
                // When the display context is `ItemDisplayContext#FIRST_PERSON_RIGHT_HAND`
                "when": "firstperson_righthand",
                "model": {
                     // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_2.json'
                    "model": "examplemod:item/example_item_2"
                }
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new SelectItemModel.Unbaked(
            new SelectItemModel.UnbakedSwitch(
                // The `SelectItemModelProperty` to use
                new DisplayContext(),
                // Switch cases based on selectable property
                List.of(
                    new SelectItemModel.SwitchCase(
                        // The list of cases to match for this model
                        List.of(ItemDisplayContext.GUI),
                        // Can be any unbaked model type
                        new CuboidItemModelWrapper.Unbaked(
                            // Points to 'assets/examplemod/models/item/example_item_1.json'
                            Identifier.fromNamespaceAndPath("examplemod", "item/example_item_1"),
                            Optional.empty(),
                            Collections.emptyList()
                        )
                    ),
                    new SelectItemModel.SwitchCase(
                        // The list of cases to match for this model
                        List.of(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND),
                        // Can be any unbaked model type
                        new CuboidItemModelWrapper.Unbaked(
                            // Points to 'assets/examplemod/models/item/example_item_2.json'
                            Identifier.fromNamespaceAndPath("examplemod", "item/example_item_2"),
                            Optional.empty(),
                            Collections.emptyList()
                        )
                    )
                )
            ),
            // The fallback model to use if no case matches
            Optional.of(
                new CuboidItemModelWrapper.Unbaked(
                    // Points to 'assets/examplemod/models/item/example_item.json'
                    ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
                    Optional.empty(),
                    Collections.emptyList()
                )
            )
        )
    );
}
```

</TabItem>
</Tabs>

Creating your own `SelectItemModelProperty` is similar to a codec-based registry object. You make a class that implements `SelectItemModelProperty<T>`, create a `Codec` to serialize and deserialize the property value, create a `MapCodec` to encode and decode the object, and register the codec to its registry via `RegisterSelectItemModelPropertyEvent` on the [mod event bus][modbus]. The `SelectItemModelProperty` has a generic `T` that represents the value to switch on. It only contains one method `get`, which takes in the current `ItemStack`, the level the stack is in, the entity holding the stack, some seeded value, and the display context of the item to return an arbitrary `T` to be interpreted by the select model.

```java
// The select property class
public record StackRarity() implements SelectItemModelProperty<Rarity> {

    // The object to register that contains the relevant codecs
    public static final SelectItemModelProperty.Type<StackRarity, Rarity> TYPE = SelectItemModelProperty.Type.create(
        // The map codec for this property
        MapCodec.unit(new StackRarity()),
        // The codec for the object being selected
        // Used to serialize the case entries ("when": <property value>)
        Rarity.CODEC
    );

    @Nullable
    @Override
    public Rarity get(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed, ItemDisplayContext displayContext) {
        // When null, uses the fallback model
        return stack.get(DataComponents.RARITY);
    }

    @Override
    public SelectItemModelProperty.Type<StackRarity, Rarity> type() {
        return TYPE;
    }
}

// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerSelectProperties(RegisterSelectItemModelPropertyEvent event) {
    event.register(
        // The name to reference as the type
        Identifier.fromNamespaceAndPath("examplemod", "rarity"),
        // The property type
        StackRarity.TYPE
    )
}
```

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:select",

        // The `SelectItemModelProperty` to use
        "property": "examplemod:rarity",
        "fallback": {
            // The fallback model to use if no case matches
            // Can be any unbaked model type
            "type": "minecraft:model",
            "model": "examplemod:item/example_item"
        },

        // Switch cases based on Selectable Property
        "cases": [
            {
                // When the rarity is `Rarity#UNCOMMON`
                "when": "uncommon",
                "model": {
                    // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_1.json'
                    "model": "examplemod:item/example_item_1"
                }
            },
            {
                // When the rarity is `Rarity#RARE`
                "when": "rare",
                "model": {
                     // Can be any unbaked model type
                    "type": "minecraft:model",
                    // Points to 'assets/examplemod/models/item/example_item_2.json'
                    "model": "examplemod:item/example_item_2"
                }
            }
        ]
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new SelectItemModel.Unbaked(
            new SelectItemModel.UnbakedSwitch(
                // The `SelectItemModelProperty` to use
                new StackRarity(),
                // Switch cases based on selectable property
                List.of(
                    new SelectItemModel.SwitchCase(
                        // The list of cases to match for this model
                        List.of(Rarity.UNCOMMON),
                        // Can be any unbaked model type
                        new CuboidItemModelWrapper.Unbaked(
                            // Points to 'assets/examplemod/models/item/example_item_1.json'
                            Identifier.fromNamespaceAndPath("examplemod", "item/example_item_1"),
                            Optional.empty(),
                            Collections.emptyList()
                        )
                    ),
                    new SelectItemModel.SwitchCase(
                        // The list of cases to match for this model
                        List.of(Rarity.RARE),
                        // Can be any unbaked model type
                        new CuboidItemModelWrapper.Unbaked(
                            // Points to 'assets/examplemod/models/item/example_item_2.json'
                            Identifier.fromNamespaceAndPath("examplemod", "item/example_item_2"),
                            Optional.empty(),
                            Collections.emptyList()
                        )
                    )
                )
            ),
            // The fallback model to use if no case matches
            Optional.of(
                new CuboidItemModelWrapper.Unbaked(
                    // Points to 'assets/examplemod/models/item/example_item.json'
                    ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
                    Optional.empty(),
                    Collections.emptyList()
                )
            )
        )
    );
}
```

</TabItem>
</Tabs>

### Conditional Models

Conditional models are the simplest out of the three. The type defines some `ConditionalItemModelProperty` to get a boolean to switch the model on. The model chosen based on whether the returned boolean is true or false. The available `ConditionalItemModelProperty`s to use can be found in `ConditionalItemModelProperties`.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:condition",

        // The `ConditionalItemModelProperty` to use
        "property": "minecraft:damaged",

        // What the boolean outcome is
        "on_true": {
            // Can be any unbaked model type
            "type": "minecraft:model",
            // Points to 'assets/examplemod/models/item/example_item_1.json'
            "model": "examplemod:item/example_item_1"
            
        },
        "on_false": {
            // Can be any unbaked model type
            "type": "minecraft:model",
            // Points to 'assets/examplemod/models/item/example_item_2.json'
            "model": "examplemod:item/example_item_2"
        }
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new ConditionalItemModel.Unbaked(
            // The property to check
            new Damaged(),
            // When the boolean is true
            new CuboidItemModelWrapper.Unbaked(
                // Points to 'assets/examplemod/models/item/example_item_1.json'
                Identifier.fromNamespaceAndPath("examplemod", "item/example_item_1"),
                Optional.empty(),
                Collections.emptyList()
            ),
            // When the boolean is false
            new CuboidItemModelWrapper.Unbaked(
                // Points to 'assets/examplemod/models/item/example_item_2.json'
                Identifier.fromNamespaceAndPath("examplemod", "item/example_item_2"),
                Optional.empty(),
                Collections.emptyList()
            )
        )
    );
}
```

</TabItem>
</Tabs>

Creating your own `ConditionalItemModelProperty` is similar to any other codec-based registry object. You make a class that implements `ConditionalItemModelProperty`, create a `MapCodec` to encode and decode the object, and register the codec to its registry via `RegisterConditionalItemModelPropertyEvent` on the [mod event bus][modbus]. The `RangeSelectItemModelProperty` only contains one method `get`, which takes in the current `ItemStack`, the level the stack is in, the entity holding the stack, some seeded value, and the display context of the item to return an arbitrary boolean to be interpreted by the conditional model (`on_true` or `on_false`).

```java
public record BarVisible() implements ConditionalItemModelProperty {

    public static final MapCodec<BarVisible> MAP_CODEC =  MapCodec.unit(new BarVisible());

    @Override
    public boolean get(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed, ItemDisplayContext context) {
        return stack.isBarVisible();
    }

    @Override
    public MapCodec<BarVisible> type() {
        return MAP_CODEC;
    }
}

// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerConditionalProperties(RegisterConditionalItemModelPropertyEvent event) {
    event.register(
        // The name to reference as the type
        Identifier.fromNamespaceAndPath("examplemod", "bar_visible"),
        // The map codec
        BarVisible.MAP_CODEC
    )
}
```

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:condition",

        // The `ConditionalItemModelProperty` to use
        "property": "examplemod:bar_visible",

        // What the boolean outcome is
        "on_true": {
            // Can be any unbaked model type
            "type": "minecraft:model",
            // Points to 'assets/examplemod/models/item/example_item_1.json'
            "model": "examplemod:item/example_item_1"
            
        },
        "on_false": {
            // Can be any unbaked model type
            "type": "minecraft:model",
            // Points to 'assets/examplemod/models/item/example_item_2.json'
            "model": "examplemod:item/example_item_2"
        }
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new ConditionalItemModel.Unbaked(
            // The property to check
            new BarVisible(),
            // When the boolean is true
            new CuboidItemModelWrapper.Unbaked(
                // Points to 'assets/examplemod/models/item/example_item_1.json'
                Identifier.fromNamespaceAndPath("examplemod", "item/example_item_1"),
                Optional.empty(),
                Collections.emptyList()
            ),
            // When the boolean is false
            new CuboidItemModelWrapper.Unbaked(
                // Points to 'assets/examplemod/models/item/example_item_2.json'
                Identifier.fromNamespaceAndPath("examplemod", "item/example_item_2"),
                Optional.empty(),
                Collections.emptyList()
            )
        )
    );
}
```

</TabItem>
</Tabs>

## Special Models

Not all models can be represented using the basic model JSON. Some models can have dynamic components, or use existing `Model`s created for a [`BlockEntityRenderer`][ber]. In these instances, there is a special model type which allows the user to specify what [features] to submit for rendering. These are known as `SpecialModelRenderer`s, which are defined within `SpecialModelRenderers`.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:special",

        // The parent model to read the particle texture and display transformation from
        // Points to 'assets/minecraft/models/item/template_skull.json'
        "base": "minecraft:item/template_skull",
        "model": {
            // The special model renderer to use
            "type": "minecraft:head",

            // Properties defined by `SkullSpecialRenderer.Unbaked`
            // The type of the skull block
            "kind": "wither_skeleton",
            // The texture to use when rendering the head
            // Points to 'assets/examplemod/textures/entity/heads/skeleton_override.png'
            "texture": "examplemod:heads/skeleton_override",
            // The animation float used to animate the head model
            "animation": 0.5
        }
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new SpecialModelWrapper.Unbaked(
            // The parent model to read the particle texture and display transformation from
            // Points to 'assets/minecraft/models/item/template_skull.json'
            Identifier.fromNamespaceAndPath("minecraft", "item/template_skull"),
            // The special model renderer to use
            new SkullSpecialRenderer.Unbaked(
                // The type of the skull block
                SkullBlock.Types.WITHER_SKELETON,
                // The texture to use when rendering the head
                // Points to 'assets/examplemod/textures/entity/heads/skeleton_override.png'
                Optional.of(
                    Identifier.fromNamespaceAndPath("examplemod", "heads/skeleton_override")
                ),
                // The animation float used to animate the head model
                0.5f
            )
        )
    );
}
```

</TabItem>
</Tabs>

Creating your own `SpecialModelRenderer` is broken into three parts: the `SpecialModelRenderer` instance used to submit the [features] used to render the item, the `SpecialModelRenderer.Unbaked` instance used to read and write to JSON, and the registration to use the renderer when as an item or, if necessary, when as a block.

First, there is the `SpecialModelRenderer`. This works similarly to any other renderer class (e.g. block entity renderers, entity renderers). It should take in the static data used during the submission process (e.g., the `Model` subclass, the `SpriteId` of the texture, etc.). There are two methods to be aware of. First, there is `extractArgument`. This is used to limit the amount of data available to the `submit` method by only supplying what is necessary from the `ItemStack`.

:::note
If you don't know what data you may need, you can just have this return the `ItemStack` in question. If you need no data from the stack, you can instead use `NoDataSpecialModelRenderer`, which implements this method for you.
:::

Next is the `submit` method. This takes in value returned from `extractArgument`, the pose stack, the collector used to submit the desired features, the packed light, the overlay texture, if the stack is foiled (e.g. enchanted), and the outline color. All feature submissions should happen in this method.

```java
public record ExampleSpecialRenderer(SpriteGetter spriteGetter, Model.Simple model, SpriteId sprite) implements SpecialModelRenderer<Boolean> {

    @Nullable
    public Boolean extractArgument(ItemStack stack) {
        // Extract the data to be used
        return stack.isBarVisible();
    }

    // Submit the features of the model
    @Override
    public void submit(Boolean argument, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        collector.submitModel(
            this.model, Unit.INSTANCE,
            poseStack, this.sprite.renderType(barVisible ? RenderType::entityCutout : RenderType::entitySolid),
            lightCoords, overlayCoords, -1, this.spriteGetter.get(this.sprite), outlineColor, null
        );
    }
}
```

Next is the `SpecialModelRenderer.Unbaked` instance. This should contain data that can be read from a file to determine what to pass into the special renderer. This also contains two methods: `bake`, which is used to construct the special renderer instance; and `type`, which defines the `MapCodec` to use for encoding/decoding to file.

```java
public record ExampleSpecialRenderer(SpriteGetter spriteGetter, Model.Simple model, SpriteId sprite) implements SpecialModelRenderer<Boolean> {

    // ...

    public record Unbaked(Identifier texture) implements SpecialModelRenderer.Unbaked {

        public static final MapCodec<ExampleSpecialRenderer.Unbaked> MAP_CODEC = Identifier.CODEC.fieldOf("texture")
            .xmap(ExampleSpecialRenderer.Unbaked::new, ExampleSpecialRenderer.Unbaked::texture);

        @Override
        public MapCodec<ExampleSpecialRenderer.Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext ctx) {
            // Resolve resource location to absolute path
            Identifier textureLoc = this.texture.withPath(path -> "textures/entity/" + path + ".png");

            // Get the model and the sprites to render
            return new ExampleSpecialRenderer(ctx.sprites(), ...);
        }
    }
}
```

Finally, we register the objects to their necessary locations. For the client items, this is done via `RegisterSpecialModelRendererEvent` on the [mod event bus][modbus]. If the special renderer should also be used as part of a `BlockEntityRenderer`, such as when rendering in some item-like context (e.g., enderman holding the block), then an `Unbaked` version for the block should be registered via `RegisterBlockModelsEvent` on the [mod event bus][modbus].

```java
// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerSpecialRenderers(RegisterSpecialModelRendererEvent event) {
    event.register(
        // The name to reference as the type
        Identifier.fromNamespaceAndPath("examplemod", "example_special"),
        // The map codec
        ExampleSpecialRenderer.Unbaked.MAP_CODEC
    );
}

// For rendering a block in an item-like context
// Assume some DeferredBlock<ExampleBlock> EXAMPLE_BLOCK
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerSpecialBlockRenderers(RegisterBlockModelsEvent event) {
    event.register(
        // The unbaked instance to use
        new SpecialBlockModelWrapper.Unbaked(
            new ExampleSpecialRenderer.Unbaked(Identifier.fromNamespaceAndPath("examplemod", "entity/example_special")),
            Optional.empty()
        ),
        // The block to render for
        EXAMPLE_BLOCK.get()
    );
}
```

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "minecraft:special",

        // The parent model to read the particle texture and display transformation from
        // Points to 'assets/minecraft/models/item/template_skull.json'
        "base": "minecraft:item/template_skull",
        "model": {
            // The special model renderer to use
            "type": "examplemod:example_special",

            // Properties defined by `ExampleSpecialRenderer.Unbaked`
            // The texture to use
            // Points to 'assets/examplemod/textures/entity/example/example_texture.png'
            "texture": "examplemod:example/example_texture"
        }
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new SpecialModelWrapper.Unbaked(
            // The parent model to read the particle texture and display transformation from
            // Points to 'assets/minecraft/models/item/template_skull.json'
            Identifier.fromNamespaceAndPath("minecraft", "item/template_skull"),
            // The special model renderer to use
            new ExampleSpecialRenderer.Unbaked(
                // The texture to use
                // Points to 'assets/examplemod/textures/entity/example/example_texture.png'
                Identifier.fromNamespaceAndPath("examplemod", "example/example_texture")
            )
        )
    );
}
```

</TabItem>
</Tabs>

## Dynamic Fluid Container

NeoForge adds an item model that constructs a dynamic fluid container, capable of re-texturing itself at runtime to match the contained fluid.

:::note
For the fluid tint to apply to the fluid texture, the item in question must have a `Capabilities.FluidHandler.ITEM` attached. If your item does not directly use `BucketItem` (not a subtype either), then you need to [register the capability to your item][capability].
:::

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "neoforge:fluid_container",

        // The textures used to construct the container
        // These are in reference to the block atlas, so they are relative to the `textures` directory
        "textures": {
            // Sets the model particle sprite
            // If not set, uses the first texture that is not null:
            // - Fluid still texture
            // - Container base texture
            // - Container cover texture, if not used as a mask
            // Points to 'assets/minecraft/textures/item/bucket.png'
            "particle": "minecraft:item/bucket",
            // Sets the texture to use on the first layer, generally the container of the fluid
            // If not set, the layer will not be added
            // Points to 'assets/minecraft/textures/item/bucket.png'
            "base": "minecraft:item/bucket",
            // Sets the texture to use as the mask for the still fluid texture
            // Areas where the fluid is seen should be pure white
            // If not set or the fluid is empty, then the layer is not drawn
            // Points to 'assets/neoforge/textures/item/mask/bucket_fluid.png'
            "fluid": "neoforge:item/mask/bucket_fluid",
            // Sets the texture to use as either
            // - The overlay texture when 'cover_is_mask' is false
            // - The mask to apply to the base texture (should be pure white to see) when 'cover_is_mask' is true
            // If not set or no base texture is set when 'cover_is_mask' is true, then the layer is not drawn
            // Points to 'assets/neoforge/textures/item/mask/bucket_fluid_cover.png'
            "cover": "neoforge:item/mask/bucket_fluid_cover",
        },

        // When true, rotates the model 180 degrees for fluids whose density is negative or zero
        // Defaults to false
        "flip_gas": true,
        // When true, uses the cover texture as a mask for the base texture
        // Defaults to true
        "cover_is_mask": true,
        // When true, sets the lightmap of the fluid texture layer to its max value
        // for fluids whose light level is greater than zero
        // Defaults to true
        "apply_fluid_luminosity": false
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<ExampleFluidContainerItem> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new DynamicFluidContainerModel.Unbaked(
            // The textures used to construct the container
            // These are in reference to the block atlas, so they are relative to the `textures` directory
            new DynamicFluidContainerModel.Textures(
                // Sets the model particle sprite
                // If not set, uses the first texture that is not null:
                // - Fluid still texture
                // - Container base texture
                // - Container cover texture, if not used as a mask
                // Points to 'assets/minecraft/textures/item/bucket.png'
                Optional.of(Identifier.withDefaultNamespace("item/bucket")),
                // Sets the texture to use on the first layer, generally the container of the fluid
                // If not set, the layer will not be added
                // Points to 'assets/minecraft/textures/item/bucket.png'
                Optional.of(Identifier.withDefaultNamespace("item/bucket")),
                // Sets the texture to use as the mask for the still fluid texture
                // Areas where the fluid is seen should be pure white
                // If not set or the fluid is empty, then the layer is not rendered
                // Points to 'assets/neoforge/textures/item/mask/bucket_fluid.png'
                Optional.of(Identifier.fromNamespaceAndPath("neoforge", "item/mask/bucket_fluid")),
                // Sets the texture to use as either
                // - The overlay texture when 'cover_is_mask' is false
                // - The mask to apply to the base texture (should be pure white to see) when 'cover_is_mask' is true
                // If not set or no base texture is set when 'cover_is_mask' is true, then the layer is not rendered
                // Points to 'assets/neoforge/textures/item/mask/bucket_fluid_cover.png'
                Optional.of(Identifier.fromNamespaceAndPath("neoforge", "item/mask/bucket_fluid_cover"))
            ),
            // When true, rotates the model 180 degrees
            // Defaults to false
            true,
            // When true, uses the cover texture as a mask for the base texture
            // Defaults to true
            true,
            // When true, sets the lightmap of the fluid texture layer to its max value
            // Defaults to true
            false
        )
    );
}
```

</TabItem>
</Tabs>

## Manually Submitting an Item for Rendering

If you need to submit an item [feature][features], such as in some `BlockEntityRenderer` or `EntityRenderer`, it can be achieved through three steps. First, the renderer in question creates an `ItemStackRenderState` to hold the state of the stack. Then, the `ItemModelResolver` updates the `ItemStackRenderState` using one of its methods to update the state to the current item being submitted. Finally, the item is submitted via `ItemStackRenderState#submit`.

The `ItemStackRenderState` keeps track of the data used for drawing. Each 'model' is given its own `ItemStackRenderState.LayerRenderState`, which contains the `BakedQuad`s to render, along with its render type, foil status, tint information, animated flag, extents, and any special renderers used. Layers are created using the `newLayer` method, and cleared for rendering using the `clear` method. If a predefined number of layers is used, then `ensureCapacity` is used to make sure there are the necessary number of `LayerRenderStates` to render properly.

:::note
[Screens][screens] use the subclass `TrackingItemStackRenderState` to hold model identity elements for caching the rendered state across frames.
:::

`ItemModelResolver` is responsible for updating the `ItemStackRenderState`. This is done through either `updateForLiving` for items held by living entities, `updateForNonLiving` for items held by other kinds of entities, and `updateForTopItem` for all other cases. These methods take in the render state, stack to render, and current display context. The other parameters update information about the held hand, level, item owner, and seeded value. Each method calls `ItemStackRenderState#clear` before calling `update` on the `ItemModel` obtained from  `DataComponents#ITEM_MODEL`. The `ItemModelResolver` can always be obtained via `Minecraft#getItemModelResolver` if you are not within some renderer context (e.g., `BlockEntityRenderer`, `EntityRenderer`).

## Custom Item Model Definitions

Creating your own `ItemModel` is broken into three parts: the `ItemModel` instance used to update the render state, the `ItemModel.Unbaked` instance used to read and write to JSON, and the registration to use the `ItemModel`.

:::warning
Please make sure to check that your required item model can not be created with the existing systems above. In most cases, it is not necessary to create a custom `ItemModel`.
:::

First, there is the `ItemModel`. This is responsible for updating the `ItemStackRenderState` such that the item is drawn correctly. It should take in the static data used during the submission process (e.g., the list of `BakedQuad`s, property information, etc.). The only method is `update`, which takes in the render state, stack, model resolver, display context, level, item owner, and some seeded value to update the `ItemStackRenderState`. `ItemStackRenderState` should be the only parameter modified, with the rest treated as read-only data.

```java
public record ExampleModelWrapper(QuadCollection quads, List<ItemTintSource> tints, ModelRenderProperties properties, Matrix4fc transformation) implements ItemModel {

    // Update the render state
    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext displayContext, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        // Set the identity used by the model
        state.appendModelIdentityElement(this);

        // Create a new layer
        ItemStackRenderState.LayerRenderState layerState = state.newLayer();

        // Sets the foil to use
        if (stack.hasFoil()) {
            layerState.setFoilType(ItemStackRenderState.FoilType.STANDARD);
            state.appendModelIdentityElement(ItemStackRenderState.FoilType.STANDARD);
        }

        // Apply the tint sources
        int tintSize = this.tints.size();
        int[] tintLayers = layerState.prepareTintLayers(tintSize);

        for (int idx = 0; idx < tintSize; idx++) {
            int tintColor = this.tints.get(idx).calculate(stack, level, owner.asLivingEntity());
            tintLayers[idx] = tintColor;
            state.appendModelIdentityElement(tintColor);
        }

        // Computes the bounds of the model
        // Used for GUI render bounds (when oversized) and item entity bobbing
        layerState.setExtents(CuboidItemModelWrapper.computeExtents(this.quads.getAll()));

        // Set the local transforms to apply for the client item
        layerState.setLocalTransform(this.transformation);

        // Set other common model properties
        this.properties.applyToLayer(layerState, displayContext);

        // Adds the quads to submit
        layerState.prepareQuadList().addAll(this.quads.getAll());

        // Set animated if it has the associated material flag
        if (this.quads.hasMaterialFlag(BakedQuad.FLAG_ANIMATED)) {
            layerState.setAnimated();
        }
    }
}
```

Next is the `ItemModel.Unbaked` instance. This should contain data that can be read from a file to determine what to pass into the item model. This also contains two methods: `bake`, which is used to construct the `ItemModel` instance; and `type`, which defines the `MapCodec` to use for encoding/decoding to file.

```java
public record ExampleModelWrapper(QuadCollection quads, List<ItemTintSource> tints, ModelRenderProperties properties, Matrix4fc transformation) implements ItemModel {

    // ...

     public record Unbaked(Identifier model, List<ItemTintSource> tints, Optional<Transformation> transformation) implements ItemModel.Unbaked {
        // The map codec to register
        public static final MapCodec<ExampleModelWrapper.Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                Identifier.CODEC.fieldOf("model").forGetter(ExampleModelWrapper.Unbaked::model),
                ItemTintSources.CODEC.listOf().optionalFieldOf("tints", List.of()).forGetter(ExampleModelWrapper.Unbaked::tints)
                Transformation.EXTENDED_CODEC.optionalFieldOf("transformation").forGetter(ExampleModelWrapper.Unbaked::transformation)
            )
            .apply(instance, ExampleModelWrapper.Unbaked::new)
        );

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            // Mark all dependencies for this item model
            resolver.markDependency(this.model);
        }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc parentTransform) {
            // Get the baked quads and return
            ModelBaker baker = context.blockModelBaker();
            ResolvedModel resolvedModel = baker.getModel(this.model);
            TextureSlots slots = resolvedModel.getTopTextureSlots();

            return new ExampleModelWrapper(
                resolvedModel.bakeTopGeometry(slots, baker, BlockModelRotation.IDENTITY),
                this.tints,
                ModelRenderProperties.fromResolvedModel(baker, resolvedModel, slots),
                Transformation.compose(parentTransform, this.transformation)
            );
        }

        @Override
        public MapCodec<ExampleModelWrapper.Unbaked> type() {
            return MAP_CODEC;
        }
    }
}
```

Then, we register the map codec via `RegisterItemModelsEvent` on the [mod event bus][modbus].

```java
// In some event handler class
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerItemModels(RegisterItemModelsEvent event) {
    event.register(
        // The name to reference as the type
        Identifier.fromNamespaceAndPath("examplemod", "render_type"),
        // The map codec
        ExampleModelWrapper.Unbaked.MAP_CODEC
    )
}
```

Finally, we can use the `ItemModel` in our JSON or as part of the datagen process.

<Tabs>
<TabItem value="json" label="JSON" default>

```json5
// For some item 'examplemod:example_item'
// JSON at 'assets/examplemod/items/example_item.json'
{
    "model": {
        "type": "examplemod:render_type",
        // Points to 'assets/examplemod/models/item/example_item.json'
        "model": "examplemod:item/example_item",
        // Any tints to apply to the model texture
        "tints": []
    }
}
```

</TabItem>

<TabItem value="datagen" label="Datagen">

```java
// Assume there is some DeferredItem<Item> EXAMPLE_ITEM
// Within an extended ModelProvider
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    itemModels.itemModelOutput.accept(
        EXAMPLE_ITEM.get(),
        new ExampleModelWrapper.Unbaked(
            // Points to 'assets/examplemod/models/item/example_item.json'
            ModelLocationUtils.getModelLocation(EXAMPLE_ITEM.get()),
            // Any tints to apply to the model texture
            List.of(),
            // The transformations to apply after the model JSON transforms
            Optional.empty()
        )
    );
}
```

</TabItem>
</Tabs>

[assets]: ../../index.md#assets
[ber]: ../../../blockentities/ber.md
[capability]: ../../../inventories/capabilities.md#registering-capabilities
[composite]: modelloaders.md#composite-model
[features]: ../../../rendering/feature.md
[itemmodel]: #manually-rendering-an-item
[modbus]: ../../../concepts/events.md#event-buses
[models]: modelsystem.md
[rl]: ../../../misc/identifier.md
[screens]: ../../../rendering/screens.md#items

## resources/client/models/modelloaders

# Custom Model Loaders

A model is simply a shape. It can be a cube, a collection of cubes, a collection of triangles, or any other geometrical shape (or collection of geometrical shape). For most contexts, it is not relevant how a model is defined, as everything will end up baked into a `QuadCollection` anyway. As such, NeoForge adds the ability to register custom model loaders that can transform any model you want into the baked format for the game to use.

## Model Loaders

The entry point for a block model remains the model JSON file. However, you can specify a `loader` field in the root of the JSON that will swap out the default loader for your own loader. A custom model loader may ignore all fields the default loader requires.

Besides the default model loader, NeoForge offers several builtin loaders, each serving a different purpose.

### Composite Model

A composite model can be used to specify different model parts in the parent and only apply some of them in a child. This is best illustrated by an example. Consider the following parent model at `examplemod:example_composite_model`:

```json5
{
    "loader": "neoforge:composite",
    // Specify model parts.
    "children": {
        // These can either be references to another model or a model itself.
        "part_1": {
            "parent": "examplemod:some_model_1"
        },
        "part_2": {
            "parent": "examplemod:some_model_2"
        }
    },
    "visibility": {
        // Disable part 2 by default.
        "part_2": false
    }
}
```

Then, we can disable and enable individual parts in a child model of `examplemod:example_composite_model`:

```json5
{
    "parent": "examplemod:example_composite_model",
    // Override visibility. If a part is missing, it will use the parent model's visibility value.
    "visibility": {
        "part_1": false,
        "part_2": true
    }
}
```

To [datagen][modeldatagen] this model, use the custom loader class `CompositeModelBuilder`.

:::warning
The composite model loader should not be used for models used by [client items][citems]. Instead, they should use the [composite model][itemcomposite] provided in the definition itself.
:::

### Empty Model

An empty model just renders nothing at all.

```json5
{
    "loader": "neoforge:empty"
}
```

### OBJ Model

The OBJ model loader allows you to use Wavefront `.obj` 3D models in the game, allowing for arbitrary shapes (including triangles, circles, etc.) to be included in a model. The `.obj` model must be placed in the `models` folder (or a subfolder thereof), and a `.mtl` file with the same name must be provided (or set manually), so for example, an OBJ model at `models/block/example.obj` must have a corresponding MTL file at `models/block/example.mtl`.

```json5
{
    "loader": "neoforge:obj",
    // Required. Reference to the model file. Note that this is relative to the namespace root, not the model folder.
    "model": "examplemod:models/example.obj",
    // Normally, .mtl files must be put into the same location as the .obj file, with only the file ending differing.
    // This will cause the loader to automatically pick them up. However, you can also set the location
    // of the .mtl file manually if needed.
    "mtl_override": "examplemod:models/example_other_name.mtl",
    // These textures can be referenced in the .mtl file as #texture0, #particle, etc.
    // This usually requires manual editing of the .mtl file.
    "textures": {
        "texture0": "minecraft:block/cobblestone",
        "particle": "minecraft:block/stone"
    },
    // Enable or disable automatic culling of the model. Optional, defaults to true.
    "automatic_culling": false,
    // Whether to shade the model or not. Optional, defaults to true.
    "shade_quads": false,
    // Some modeling programs will assume V=0 to be bottom instead of the top. This property flips the Vs upside-down.
    // Optional, defaults to false.
    "flip_v": true,
    // Whether to enable emissivity or not. Optional, defaults to true.
    "emissive_ambient": false
}
```

To [datagen][modeldatagen] this model, use the custom loader class `ObjModelBuilder`.

### Creating Custom Model Loaders

To create your own model loader, you need four classes, plus an event handler:

- An `UnbakedModelLoader` class
- An `UnbakedGeometry` class, usually an `ExtendedUnbakedGeometry` instance
- An `UnbakedModel` class, usually an `AbstractUnbakedModel` instance
- A `QuadCollection` class to hold the baked quads, usually the class itself
- A [client-side][sides] [event handler][event] for `ModelEvent.RegisterLoaders` that registers the unbaked model loader
- Optional: A [client-side][sides] [event handler][event] for `AddClientReloadListenersEvent` for model loaders that cache data about what is being loaded

To illustrate how these classes are connected, we will follow a model being loaded:

- During model loading, a model JSON with the `loader` property set to your loader is passed to your unbaked model loader. The loader then reads the model JSON and returns an `UnbakedModel` object using the model JSON's properties and an `UnbakedGeometry` with the model's unbaked quads.
- During model baking, `UnbakedGeometry#bake` is called, returning a `QuadCollection`.
- During model rendering, the `QuadCollection`, along with any other information required by the [client item][citems] or [block state definition][blockstatedefinition] is used in rendering.

:::note
If you are creating a custom model loader for a model used by an item or block state, depending on the use case, it might be better to create a new `ItemModel` or `BlockStateModel` instead. For example, a model that uses or generates `QuadCollection`s would make more sense as an `ItemModel` or `BlockStateModel`, while a model that parses a different data format (like `.obj`) should use a new model loader.
:::

Let's illustrate this further through a basic class setup. The loader class is named `MyUnbakedModelLoader`, the unbaked class is named `MyUnbakedModel`, and the unbaked geometry is called `MyUnbakedGeometry`. We will also assume that the model loader requires some cache:

```java
// This is the class used to load the model into its unbaked format
public class MyUnbakedModelLoader implements UnbakedModelLoader<MyUnbakedModel>, ResourceManagerReloadListener {
    // It is highly recommended to use a singleton pattern for unbaked model loaders, as all models can be loaded through one loader.
    public static final MyUnbakedModelLoader INSTANCE = new MyUnbakedModelLoader();
    // The id we will use to register this loader. Also used in the loader datagen class.
    public static final Identifier ID = Identifier.fromNamespaceAndPath("examplemod", "my_custom_loader");

    // In accordance with the singleton pattern, make the constructor private.        
    private MyUnbakedModelLoader() {}

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // Handle any cache clearing logic
    }

    @Override
    public MyUnbakedModel read(JsonObject obj, JsonDeserializationContext context) throws JsonParseException {
        // Use the given JsonObject and, if needed, the JsonDeserializationContext to get properties from the model JSON.
        // The MyUnbakedModel constructor may have constructor parameters (see below).

        // Read the data used to create the quads
        MyUnbakedGeometry geometry;

        // For the basic parameters provided by vanilla and NeoForge, you can use the StandardModelParameters
        StandardModelParameters params = StandardModelParameters.parse(obj, context);

        return new MyUnbakedModel(params, geometry);
    }
}

// Holds the unbaked quads to render
// Other information that is stored in the unbaked model should be passed to the context map
public class MyUnbakedGeometry implements ExtendedUnbakedGeometry {

    public MyUnbakedGeometry(...) {
        // Store the unbaked quads to bake
    }

    // Method responsible for model baking, returning the quad collection. Parameters in this method are:
    // - The map of texture names to their associated materials.
    // - The model baker. Can be used for getting sub-models to bake and getting sprites from the texture slots.
    // - The model state. This holds the transformations from the blockstate file, typically from rotations and the uvlock.
    // - The name of the model.
    // - A ContextMap of settings provided by NeoForge and your unbaked model. See the 'NeoForgeModelProperties' class for all available properties.
    @Override
    public QuadCollection bake(TextureSlots textureSlots, ModelBaker baker, ModelState state, ModelDebugName debugName, ContextMap additionalProperties) {
        // The builder to create the collection
        var builder = new QuadCollection.Builder();
        // Build the quads for baking
        builder.addUnculledFace(...); // or addCulledFace(Direction, BakedQuad)
        // Create the quad collection
        return builder.build();
    }
}

// The unbaked model contains all the information read from the JSON.
// It provides the basic settings and geometry.
// Using AbstractUnbakedModel sets the Vanilla and NeoForge properties methods
public class MyUnbakedModel extends AbstractUnbakedModel {

    private final MyUnbakedGeometry geometry;

    public MyUnbakedModel(StandardModelParameters params, MyUnbakedGeometry geometry) {
        super(params);
        this.geometry = geometry;
    }

    @Override
    public UnbakedGeometry geometry() {
        // The geometry to used to construct the baked quads
        return this.geometry;
    }

    @Override
    public void fillAdditionalProperties(ContextMap.Builder propertiesBuilder) {
        super.fillAdditionalProperties(propertiesBuilder);
        // Add additional properties below by calling withParameter(ContextKey<T>, T)
        // They can then be accessed in the ContextMap provided in UnbakedGeometry#bake
    }
}
```

When all is done, don't forget to actually register your loader:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerLoaders(ModelEvent.RegisterLoaders event) {
    event.register(MyUnbakedModelLoader.ID, MyUnbakedModelLoader.INSTANCE);
}

// If you are caching data in the model loader:
@SubscribeEvent // on the mod event bus only on the physical client
public static void addClientResourceListeners(AddClientReloadListenersEvent event) {
    // Register the listener with our id
    event.addListener(MyUnbakedModelLoader.ID, MyUnbakedModelLoader.INSTANCE);
    // Add a dependency for our model loader to run before models are loaded
    // Allows the cache to be cleared before the new data is populated
    event.addDependency(MyUnbakedModelLoader.ID, VanillaClientListeners.MODELS);
}
```

#### Model Loader Datagen

Of course, we can also [datagen] our models. To do so, we need a class that extends `CustomLoaderBuilder`:

```java
public class MyLoaderBuilder extends CustomLoaderBuilder {
    public MyLoaderBuilder() {
        super(
            // Your model loader's id.
            MyUnbakedModelLoader.ID,
            // Whether the loader allows inline vanilla elements as a fallback if the loader is absent.
            false
        );
    }
    
    // Add fields and setters for the fields here. The fields can then be used below.

    @Override
    protected CustomLoaderBuilder copyInternal() {
        // Create a new instance of your loader builder and copy the properties from this builder
        // to the new instance.
        MyLoaderBuilder builder = new MyLoaderBuilder();
        // builder.<field> = this.<field>;
        return builder;
    }
    
    // Serialize the model to JSON.
    @Override
    public JsonObject toJson(JsonObject json) {
        // Add your fields to the given JsonObject.
        // Then call super, which adds the loader property and some other things.
        return super.toJson(json);
    }
}
```

To use this loader builder, do the following during block (or item) [model datagen][modeldatagen]:

```java
// This assumes an extension of ModelProvider and a DeferredBlock<Block> EXAMPLE_BLOCK.
// The parameter for customLoader() is a Supplier to construct the builder and a Consumer to set to associated properties.
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    blockModels.createTrivialBlock(
        // The block to generate the model for
        EXAMPLE_BLOCK.get(),
        TexturedModel.createDefault(
            // A mapping used to get the textures
            block -> new TextureMapping().put(
                TextureSlot.ALL, TextureMapping.getBlockTexture(block)
            ),
            // The model template builder used to create the JSON
            ExtendedModelTemplateBuilder.builder()
                // Say we are using a custom model loader
                .customLoader(MyLoaderBuilder::new, loader -> {
                    // Set any required fields here
                })
                // Textures required by the model
                .requiredTextureSlot(TextureSlot.ALL)
                // Call build once complete
                .build()
        )
    );
}
```

#### Visibility

The default implementation of `CustomLoaderBuilder` holds methods for applying visibility. You may choose to use or ignore the `visibility` property in your model loader. Currently, only the [composite model loader][composite] and [OBJ loader][obj] make use of this property.

## Block State Model Loaders

As block state models are considered separate from the model JSON file, there are also custom NeoForge loaders, handled by specifying a `type` in a variant or multipart. A custom block state model loader may ignore all fields the loader requires.

### Composite Block State Model

A composite block state model can be used to render multiple `BlockStateModel`s together.

```json5
{
    "variants": {
        "": {
            "type": "neoforge:composite",
            // Specify model parts.
            "models": [
                // These must be inlined block state models
                {
                    "variants": {
                        // ...
                    }
                },
                {
                    "multipart": [
                        // ...
                    ]
                }
                // ...
            ]
        }
    }
}
```

To [datagen][modeldatagen] this block state model, use the custom loader class `CompositeBlockStateModelBuilder`.

### Reusing the Default Model Loader

In some contexts, it makes sense to reuse the vanilla model loader and just building your model logic on top of that instead of outright replacing it. We can do so using a neat trick: in the model loader, we simply remove the `loader` property and send it back to the model deserializer, tricking it into thinking that it is a regular unbaked model now. Then, we can modify the model or its geometry before the baking process, where we can do whatever way we want.

```java
public class MyUnbakedModelLoader implements UnbakedModelLoader<MyUnbakedModel> {
    public static final MyUnbakedModelLoader INSTANCE = new MyUnbakedModelLoader();
    public static final Identifier ID = Identifier.fromNamespaceAndPath("examplemod", "my_custom_loader");
    
    private MyUnbakedModelLoader() {}

    @Override
    public MyUnbakedModel read(JsonObject jsonObject, JsonDeserializationContext context) throws JsonParseException {
        // Trick the deserializer into thinking this is a normal model by removing the loader field
        // Then, pass it to the deserializer.
        jsonObject.remove("loader");
        UnbakedModel model = context.deserialize(jsonObject, UnbakedModel.class);
        return new MyUnbakedModel(model, /* other parameters here */);
    }
}

// We extend the delegate class as that stores the wrapped model
public class MyUnbakedModel extends DelegateUnbakedModel {

    // Store the model for use below
    public MyUnbakedModel(UnbakedModel model, /* other parameters here */) {
       super(model);
    }
}
```

### Creating Custom Block State Model Loaders

To create your own block state model loader, you need five classes, plus an event handler:

- A `CustomUnbakedBlockStateModel` class to load the block state model
- A `BlockStateModel` class to bake the model, usually a `DynamicBlockStateModel` instance
- A `BlockStateModelPart.Unbaked` to load the model JSON
- A `ModelState` to apply any transformations to a given face or model 
- A `BlockStateModelPart` to hold the quads, ambient occlusion, and particle texture, commonly a `SimpleModelWrapper`
- A [client-side][sides] [event handler][event] for `RegisterBlockStateModels` that registers the codec for the unbaked block state model loader

To illustrate how these classes are connected, we will follow a block state model being loaded:

- During definition loading, a block state model within a variant, multipart, or [custom definition][customdefinition] with the `type` property set to your loader is decoded to your `CustomUnbakedBlockStateModel`.
- During model baking, `CustomUnbakedBlockStateModel#bake` is called, returning a `BlockStateModel`, which contains some list of `BlockStateModelPart`s.
- During model rendering, `BlockStateModel#collectParts` collects the list of `BlockStateModelPart`s to render.

Let's illustrate this further through a basic class setup. The baked model is named `MyBlockStateModel`, the unbaked class is an inner record `MyBlockStateModel.Unbaked`, model parts is called `MyBlockStateModelPart`, the unbaked part class is an inner record `MyBlockStateModelPart.Unbaked`, and the `ModelState` is named `MyModelState`:

```java
// The model state used to apply the necessary transformations
// If you are using an intermediate object to hold the model state, it must be transformable to a ModelState
public class MyModelState implements ModelState {

    // Used for the unbaked block model part
    public static final Codec<MyModelState> CODEC = Codec.unit(new MyModelState());

    public MyModelState() {}

    @Override
    public Transformation transformation() {
        // Returns the model rotation to apply to the baking vertices
        return Transformation.identity();
    }

    @Override
    public Matrix4fc faceTransformation(Direction direction) {
        // Returns the matrix that is applied to a given face on the model after the transformation
        // This is currently unused in Vanilla
        return NO_TRANSFORM;
    }

    @Override
    public Matrix4fc inverseFaceTransformation(Direction direction) {
        // Returns the inverse of faceTransformation that is applied to a given face on the model
        // This is passed to the FaceBakery
        return NO_TRANSFORM;
    }
}

// The model part representing a baked model
// useAmbientOcclusion and particleMaterial are implemented as part of the record
public record MyBlockStateModelPart(QuadCollection quads, boolean useAmbientOcclusion, Material.Baked particleMaterial) implements BlockStateModelPart {

    // Get the baked quads to render
    @Override
    List<BakedQuad> getQuads(@Nullable Direction direction) {
        return this.quads.getQuads(direction);
    }

    // The flags of the materials backing the quads.
    @Override
    public int materialFlags() {
        return this.quads.materialFlags();
    }

    // The unbaked model that is read from the block state json
    public record Unbaked(Identifier modelLocation, MyModelState modelState) implements BlockStateModelPart.Unbaked {

        // Used for the unbaked block state model
        public static final MapCodec<MyBlockStateModelPart.Unbaked> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                Identifier.CODEC.fieldOf("model").forGetter(MyBlockStateModelPart.Unbaked::modelLocation),
                MyModelState.CODEC.fieldOf("state").forGetter(MyBlockStateModelPart.Unbaked::modelState)
            ).apply(instance, MyBlockStateModelPart.Unbaked::new)
        );

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            // Mark any models used by the model part
            resolver.markDependency(this.modelLocation);
        }

        @Override
        public BlockStateModelPart bake(ModelBaker baker) {
            // Get the model to bake
            ResolvedModel resolvedModel = baker.getModel(this.modelLocation);

            // Get the necessary settings for the model part
            TextureSlots slots = resolvedModel.getTopTextureSlots();
            boolean ao = resolvedModel.getTopAmbientOcclusion();
            Material.Baked particle = resolvedModel.resolveParticleMaterial(slots, baker);
            QuadCollection quads = resolvedModel.bakeTopGeometry(slots, baker, this.modelState);
            
            // Return the baked part
            return new MyBlockStateModelPart(quads, ao, particle);
        }
    }
}

// The state model representing the baked block state
public record MyBlockStateModel(MyBlockStateModelPart model) implements DynamicBlockStateModel {

    // Sets the particle material
    // While it needs to be implemented, any actual logic should be delegated to the level-aware version
    @Override
    public Material.Baked particleMaterial() {
        return this.model.particleMaterial();
    }

    // The flags of the materials backing the quads.
    // While it needs to be implemented, any actual logic should be delegated to the level-aware version
    @Override
    public int materialFlags() {
        return this.quads.materialFlags();
    }

    // This effectively acts as a key to reuse geometry previous produced. This should generally be as deterministic as possible.
    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return this;
    }

    // Method responsible for collecting the parts to be rendered. Parameters in this method are:
    // - The getter for the blocks and tints, usually the level.
    // - The position of the block to render.
    // - The state of the block.
    // - A random instance.
    // - This list of model parts to be rendered. Add your model parts here.
    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        // If you want the block rendered to be dependent on the block entity (e.g., your block entity implements `BlockEntity#getModelData`)
        // You can call `BlockAndTintGetter#getModelData` with the block position
        // You can read the property using `get` with the `ModelProperty` key
        // Remember that your block entity should call `BlockEntity#requestModelDataUpdate` to sync the model data to the client
        ModelData data = level.getModelData(pos);

        // Add the model to be rendered
        parts.add(this.model);
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        // Override this if you want to use the level to determine what particle to render
        return self().particleMaterial();
    }

    @Override
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        // Override this if you want to use the level to determine what material flags the model has
        return self().materialFlags();
    }

    // The unbaked model that is read from the block state json
    public record Unbaked(MyBlockStateModelPart.Unbaked model) implements CustomUnbakedBlockStateModel {

        // The codec to register
        public static final MapCodec<MyBlockStateModel.Unbaked> CODEC = MyBlockStateModelPart.Unbaked.CODEC.xmap(
            MyBlockStateModel.Unbaked::new, MyBlockStateModel.Unbaked::model
        );
        public static final Identifier ID = Identifier.fromNamespaceAndPath("examplemod", "my_custom_model_loader");

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            // Mark any models used by the state model
            this.model.resolveDependencies(resolver);
        }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            // Bake the model parts and pass into the block state model
            return new MyBlockStateModel(this.model.bake(baker));
        }
    }
}
```

When all is done, don't forget to actually register your loader:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerDefinitions(RegisterBlockStateModels event) {
    event.registerModel(MyBlockStateModel.Unbaked.ID, MyBlockStateModel.Unbaked.CODEC);
}
```

#### State Model Loader Datagen

Of course, we can also [datagen] our models. To do so, we need a class that extends `CustomBlockStateModelBuilder`:

```java
// The builder used to construct the block state JSON
public class MyBlockStateModelBuilder extends CustomBlockStateModelBuilder {

    private MyBlockStateModelPart.Unbaked model;

    public MyBlockStateModelBuilder() {}
    
    // Add fields and setters for the fields here. The fields can then be used below.

    @Override
    public MyBlockStateModelBuilder with(VariantMutator variantMutator) {
        // If you want to apply any mutators that assumes your unbaked model part is a `Variant`
        // If not, this should do nothing
        return this;
    }

    // This is for generalized unbaked blockstate models
    @Override
    public MyBlockStateModelBuilder with(UnbakedMutator unbakedMutator) {
        var result = new MyBlockStateModelBuilder();

        if (this.model != null) {
            result.model = unbakedMutator.apply(this.model);
        }

        return result;
    }

    // Converts the builder to its unbaked variant to encode
    @Override
    public CustomUnbakedBlockStateModel toUnbaked() {
        return new MyBlockStateModel.Unbaked(this.model);
    }
}
```

To use this state definition loader builder, do the following during block (or item) [model datagen][modeldatagen]:

```java
// This assumes an extension of ModelProvider and a DeferredBlock<Block> EXAMPLE_BLOCK.
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    blockModels.blockStateOutput.accept(
        MultiVariantGenerator.dispatch(
            // The block to generate the model for
            EXAMPLE_BLOCK.get(),
            // Our custom block state builder
            MultiVariant.of(new CustomBlockStateModelBuilder().with(...))
        )
    );
}
```

This will generate a model like so:

```json5
{
  "variants": {
    "": {
        "type": "examplemod:my_custom_model_loader"
        // Other fields
    }
  }
}
```

## Block State Definition Loaders

While individual block state models handle the loading of a single block state, block state definition loaders handle the entire loading of a block state file, handled by specifying a `neoforge:definition_type`. A custom block state definition loader may ignore all fields the loader requires.

### Creating Custom Block State Definition Loaders

To create your own block state definition loader, you need two classes, plus an event handler:

- A `CustomBlockModelDefinition` class to load the block state definition
- A `BlockStateModel.UnbakedRoot` class to bake a block state to its `BlockStateModel`
- A [client-side][sides] [event handler][event] for `RegisterBlockStateModels` that registers the codec for the unbaked block state model loader

To illustrate how these classes are connected, we will follow a block state model being loaded:

- During definition loading, a block state definition with the `neoforge:definition_type` property set to your loader is decoded to a `CustomBlockModelDefinition`.
- Then, `CustomBlockModelDefinition#instantiate` is called to map all possible block states to their `BlockStateModel.UnbakedRoot`. For simple cases, this is constructed via `BlockStateModel.Unbaked#asRoot`. Complicated instances create their own `BlockStateModel.UnbakedRoot`.
- During model baking, `BlockStateModel.UnbakedRoot#bake` is called, returning a `BlockStateModel` for some `BlockState`.

Let's illustrate this further through a basic class setup. The block model definition is named `MyBlockModelDefinition` and we will reuse `BlockStateModel.Unbaked#asRoot` to construct the `BlockStateModel.UnbakedRoot`:

```java
public record MyBlockModelDefinition(MyBlockStateModel.Unbaked model) implements CustomBlockModelDefinition {

    // The codec to register
    public static final MapCodec<MyBlockModelDefinition> CODEC = MyBlockStateModel.Unbaked.CODEC.xmap(
        MyBlockModelDefinition::new, MyBlockModelDefinition::model
    );
    public static final Identifier ID = Identifier.fromNamespaceAndPath("examplemod", "my_custom_definition_loader");

    // This method maps all possible states to some unbaked root
    // As the root will generally share block states models, they are typically operated using a `ModelBaker.SharedOperationKey` to cache the loading model
    @Override
    public Map<BlockState, BlockStateModel.UnbakedRoot> instantiate(StateDefinition<Block, BlockState> states, Supplier<String> sourceSupplier) {
        Map<BlockState, BlockStateModel.UnbakedRoot> result = new HashMap<>();

        // Handle for all possible states
        var unbakedRoot = this.model.asRoot();
        states.getPossibleStates().forEach(state -> result.put(state, unbakedRoot));

        return result;
    }

    @Override
    public MapCodec<? extends CustomBlockModelDefinition> codec() {
        return CODEC;
    }
}
```

When all is done, don't forget to actually register your loader:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerDefinitions(RegisterBlockStateModels event) {
    event.registerDefinition(MyBlockModelDefinition.ID, MyBlockModelDefinition.CODEC);
}
```

#### State Definition Loader Datagen

Of course, we can also [datagen] our definitions. To do so, we need a class that extends `BlockModelDefinitionGenerator`:

```java
public class MyBlockModelDefinitionGenerator implements BlockModelDefinitionGenerator {

    private final Block block;
    private final MyBlockStateModelBuilder builder;

    private MyBlockModelDefinitionGenerator(Block block, MyBlockStateModelBuilder builder) {
        this.block = block;
        this.builder = builder;
    }

    public static MyBlockModelDefinitionGenerator dispatch(Block block, MyBlockStateModelBuilder builder) {
        return new MyBlockModelDefinitionGenerator(block, builder);
    }

    @Override
    public Block block() {
        // Returns the block you are generating the definition file for
        return this.block;
    }

    @Override
    public BlockModelDefinition create() {
        // Creates the block model definition used to encode and decode the file
        return new MyBlockModelDefinition(this.builder.toUnbaked());
    }
} 
```

To use this state definition loader builder, do the following during block (or item) [model datagen][modeldatagen]:

```java
// This assumes a DeferredBlock<Block> EXAMPLE_BLOCK.
@Override
protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
    blockModels.blockStateOutput.accept(
        MyBlockModelDefinitionGenerator.dispatch(
            // The block to generate the model for
            EXAMPLE_BLOCK.get(),
            new CustomBlockStateModelBuilder(...)
        )
    );
}
```

This will generate a model like so:

```json5
{
    "neoforge:definition_type": "examplemod:my_custom_definition_loader"
    // Other fields
}
```

[citems]: items.md
[composite]: #composite-model
[customdefinition]: #block-state-definition-loaders
[datagen]: ../../index.md#data-generation
[event]: ../../../concepts/events.md#registering-an-event-handler
[itemcomposite]: items.md#composite-models
[modeldatagen]: datagen.md
[obj]: #obj-model
[sides]: ../../../concepts/sides.md

## resources/client/models/modelsystem

# Understanding the Model System

Models within Minecraft are simply a list of quads with attached textures. Each part of the modeling process has their own separate implementation, with the underlying model JSON deserialized into an `UnbakedModel`. In the end, each part of the pipelines takes in some `List<BakedQuad>` and properties necessary for their own pipelines. Some [block entity renderers][ber] also make use of these models. There is no limit to how complex a model may be.

Models are stored in the `ModelManager`, which can be accessed through `Minecraft.getInstance().getModelManager()`. For the item pipeline, you can get the associated [`ItemModel`][itemmodels] via `ModelManager#getItemModel` by passing in a [`Identifier`][rl]. For the block state pipeline, you can get the associated `BlockStateModel` via `ModelManager.getBlockStateModelSet().get()` by passing in a `BlockState`. Mods will basically always reuse a model that was previously automatically loaded and baked.

## Common Models and Geometry

The basic model JSON (in `assets/<namespace>/models`) are deserialized into an `UnbakedModel`. The `UnbakedModel` is generally one step short of its baked output, containing some general form of the general properties. The most important thing it contains is the `UnbakedGeometry` via `UnbakedModel#geometry`, which represents the data to become `BakedQuad`s. These quads are inlined into the item and block state model by (eventually) calling `UnbakedGeometry#bake`. This commonly constructs a `QuadCollection`, which contains that list of `BakedQuad`s which can be rendered at anytime or only if a given direction is not culled. Now, a quad compares to a triangle in a modeling program (and in most other games), however due to Minecraft's general focus on squares, the developers elected to use quads (4 vertices) instead of triangles (3 vertices) for rendering in Minecraft.

The `UnbakedModel` contains information that is either used by the [block state definition][bsd], [item models][itemmodelsection], or both. For example, `useAmbientOcclusion` is used exclusively by the block state definition, `guiLight` and `transforms` are used exclusively by the item model, and `textureSlots` and `parent` are used by both.

During the baking process, every `UnbakedModel` is wrapped in a `ResolvedModel` that are obtained by the `ModelBaker` for an item or block state. As the name implies, a `ResolvedModel` is an `UnbakedModel` with all lingering references resolved. The associated data can then be obtained from the `getTop*` methods, which compute the properties and geometry from the current model and its parents. Baking the `ResolvedModel` to its `QuadCollection` is typically done here by calling `ResolvedModel#bakeTopGeometry`.

## Block State Definitions

The block state definition JSON (in `assets/<namespace>/blockstates`) is compiled and baked into a `BlockStateModel` for every `BlockState`. The process of creating the `BlockStateModel` goes like so:

- During the loading process:
    - The block state definition JSON is loaded into a `BlockStateModel.UnbakedRoot`. The root is a general shared cache system used to link a `BlockState` to some set of `BlockStateModel`s.
    - The `BlockStateModel.UnbakedRoot` loads in the `BlockStateModel.Unbaked` and gets ready to link them to their appropriate `BlockState`.
    - The `BlockStateModel.Unbaked` loads in its `BlockStateModelPart.Unbaked`, which is used to get the common `UnbakedModel` (or more specifically the `ResolvedModel`).
- During the baking process:
    - `BlockStateModel.UnbakedRoot#bake` is called for every `BlockState`.
    - `BlockStateModel.Unbaked#bake` is called for a given `BlockState`, creating a `BlockStateModel`.
    - `BlockStateModelPart.Unbaked#bake` is called for the model parts within a `BlockStateModel`, inlining the `ResolvedModel` to a `QuadCollection`, along with getting the ambient occlusion settings, the particle icon, and the render type by default.

The most important method within `BlockStateModel` is `collectParts`, which is responsible for appending to the list of `BlockStateModelPart`s to render. Remember that every `BlockStateModelPart` contains its list of `BakedQuad`s, via `BlockStateModelPart#getQuads`, which is then uploaded to the vertex consumer and rendered. `collectParts` has five parameters:

- A `BlockAndTintGetter`: A representation of the level the `BlockState` is rendered within.
- A `BlockPos`: The position that the block is rendered at.
- A `BlockState`: The [blockstate] being rendered. May be null, indicating that an item is being rendered.
- A `RandomSource`: A client-bound random source you can use for randomization.
- A `List<BlockStateModelPart>`: The list that should receive the parts to render.

### Model Data

Sometimes, a `BlockStateModel` may rely on the `BlockEntity` to determine what `BlockStateModelPart`s to choose in `collectParts`. NeoForge provides the `ModelData` system to sync and pass data from the `BlockEntity`. To do so, a `BlockEntity` must implement `getModelData` and return the data it wants to sync. The data can then be sent to the client by calling `BlockEntity#requestModelDataUpdate`. Then, within `collectParts`, `getModelData` can be called on the `BlockAndTintGetter` with the `BlockPos` to get the data.

## Item Models

The [client item][clientitem] JSON (in `assets/<namespace>/items`) is compiled and baked into an `ItemModel` for a given `Item` to be used by the `ItemStack`. The process of creating the `ItemModel` goes like so:

- During the loading process:
    - The client item JSON is loaded into a `ClientItem`. This holds the item model and some general properties for how it should be rendered.
    - The `ClientItem` loads in the `ItemModel.Unbaked`.
- During the baking process:
    - `ItemModel.Unbaked#bake` is called for every `Item`, inlining the `ResolvedModel` to a `List<BakedQuad>`, along with some general `ModelRenderProperties` and the render type if the `Item` is a `BlockItem`.

Information about item rendering can be found in the [Manually Rendering an Item][itemmodels] section.

### Perspectives

Minecraft's render engine recognizes a total of 8 perspective types (9 if you include the in-code fallback) for item rendering. These are used in a model JSON's `display` block, and represented in code through the `ItemDisplayContext` enum. These are normally passed from the `UnbakedModel` to a `ModelRenderProperties` in the `ItemModel`, which is then applied to the `ItemStackRenderState` via `ModelRenderProperties#applyToLayer`.

| Enum value                | JSON key                  | Usage                                                                                                            |
|---------------------------|---------------------------|------------------------------------------------------------------------------------------------------------------|
| `THIRD_PERSON_RIGHT_HAND` | `"thirdperson_righthand"` | Right hand in third person (F5 view, or on other players)                                                        |
| `THIRD_PERSON_LEFT_HAND`  | `"thirdperson_lefthand"`  | Left hand in third person (F5 view, or on other players)                                                         |
| `FIRST_PERSON_RIGHT_HAND` | `"firstperson_righthand"` | Right hand in first person                                                                                       |
| `FIRST_PERSON_LEFT_HAND`  | `"firstperson_lefthand"`  | Left hand in first person                                                                                        |
| `HEAD`                    | `"head"`                  | When in a player's head armor slot (often only achievable via commands)                                          |
| `GUI`                     | `"gui"`                   | Inventories, player hotbar                                                                                       |
| `GROUND`                  | `"ground"`                | Dropped items; note that the rotation of the dropped item is handled by the dropped item renderer, not the model |
| `FIXED`                   | `"fixed"`                 | Item frames                                                                                                      |
| `ON_SHELF`                | `"on_shelf"`              | On shelf blocks                                                                                                      |
| `NONE`                    | `"none"`                  | Fallback purposes in code, should not be used in JSON                                                            |

NeoForge allows the `ItemDisplayContext` to be [extended] for use in custom render calls. Modded `ItemDisplayContext`s may specify a fallback transform to use if none is specified in the model. Otherwise, behavior will be the same as vanilla.

## Modifying a Baking Result

Modifying an existing block state model or item stack model in-code can typically be done by wrapping the model in some sort of delegate. Block state models have `DelegateBlockStateModel`, while item stack models do not have an existing implementation. Your implementation can then override only select methods, like so:

```java
// For block states
public class MyDelegateBlockStateModel extends DelegateBlockStateModel {
    // Pass the original model to super.
    public MyDelegateBlockStateModel(BlockStateModel originalModel) {
        super(originalModel);
    }
    
    // Override whatever methods you want here. You may also access originalModel if needed.
}

// For item models
public class MyDelegateItemModel implements ItemModel {

    private final ItemModel originalModel;

    public MyDelegateItemModel(ItemModel originalModel) {
        this.originalModel = originalModel;
    }

    // Override whatever methods you want here. You may also access originalModel if needed.
    @Override
    public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext displayContext, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed
    ) {
        this.originalModel.update(renderState, stack, resolver, displayContext, level, owner, seed);
    }
}
```

After writing your model wrapper class, you must apply the wrappers to the models it should affect. Do so in a [client-side][sides] [event handler][event] for `ModelEvent.ModifyBakingResult` on the [**mod event bus**][modbus]:

```java
@SubscribeEvent // on the mod event bus only on the physical client
public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
    // For block state models
    event.getBakingResult().blockStateModels().computeIfPresent(
        // The block state of the model to modify.
        MyBlocksClass.EXAMPLE_BLOCK.get().defaultBlockState(),
        // A BiFunction with the location and the original models as parameters, returning the new model.
        (location, model) -> new MyDelegateBakedModel(model);
    );

    // For item models
    event.getBakingResult().itemStackModels().computeIfPresent(
        // The resource location the model to modify.
        // Typically the item registry name; however, can be anything due to the ITEM_MODEL data component
        MyItemsClass.EXAMPLE_ITEM.getKey().identifier(),
        // A BiFunction with the location and the original models as parameters, returning the new model.
        (location, model) -> new MyDelegateItemModel(model);
    );
}
```

:::warning
It is generally encouraged to use a [custom model loader][modelloader] over wrapping baked models in `ModelEvent.ModifyBakingResult` when possible. Custom model loaders can also use delegate models if needed.
:::

[ao]: https://en.wikipedia.org/wiki/Ambient_occlusion
[ber]: ../../../blockentities/ber.md
[blockstate]: ../../../blocks/states.md
[bsd]: #block-state-definitions
[clientitem]: items.md
[event]: ../../../concepts/events.md
[extended]: ../../../advanced/extensibleenums.md#creating-an-enum-entry
[itemmodels]: items.md#manually-rendering-an-item
[itemmodelsection]: #item-models
[livingentity]: ../../../entities/livingentity.md
[modbus]: ../../../concepts/events.md#event-buses
[modelloader]: modelloaders.md
[rl]: ../../../misc/identifier.md
[perspective]: #perspectives
[rendertype]: index.md#render-types
[sides]: ../../../concepts/sides.md

## resources/client/particles

# Particles

Particles are visual effects commonly spawned using their associated particle type. They can be spawned both client and server [side], but being mostly visual in nature, critical parts exist only on the physical (and logical) client side.

This article covers the construction and use of particle types and particle descriptions. For more rendering-specific information, see the companion [client particles][clientparticle] article.

## Registering `ParticleType`s

Particles are registered using `ParticleType`s. These work similar to `EntityType`s or `BlockEntityType`s, in that there's a `Particle` class - every spawned particle is an instance of that class -, and then there's the `ParticleType` class, holding some common information, that is used for registration. `ParticleType`s are a [registry], which means that we want to register them using a `DeferredRegister` like all other registered objects:

```java
public class MyParticleTypes {
    // Assuming that your mod id is examplemod
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, "examplemod");
    
    // The easiest way to add new particle types is reusing vanilla's SimpleParticleType.
    // Implementing a custom ParticleType is also possible, see below.
    public static final Supplier<SimpleParticleType> MY_QUAD_PARTICLE = PARTICLE_TYPES.register(
        // The name of the particle type.
        "my_quad_particle",
        // The supplier. The boolean parameter denotes whether setting the Particles option in the
        // video settings to Minimal will affect this particle type or not; this is false for
        // most vanilla particles, but true for e.g. explosions, campfire smoke, or squid ink.
        () -> new SimpleParticleType(false)
    );
}
```

:::info
A `ParticleType` is only necessary if you need to work with particles on the server side. The client can also use `Particle`s directly.
:::

## Custom `ParticleType`s

While for most cases `SimpleParticleType` suffices, it is sometimes necessary to attach additional data to the particle on the server side. This is where a custom `ParticleType` and an associated custom `ParticleOptions` are required. Let's start with the `ParticleOptions`, as that is where the information is actually stored:

```java
public class MyParticleOptions implements ParticleOptions {
    
    // A map codec defining additional information for the particle, used e.g. in commands.
    // Since there is no information in our type, use a unit map codec;
    // this corresponds to using an empty string in a command.
    public static final MapCodec<MyParticleOptions> CODEC = MapCodec.unit(new MyParticleOptions());

    // Read and write information to the network buffer.
    public static final StreamCodec<ByteBuf, MyParticleOptions> STREAM_CODEC = StreamCodec.unit(new MyParticleOptions());

    // Does not need any parameters, but may define any fields necessary for the particle to work.
    public MyParticleOptions() {}

    @Override
    public ParticleType<?> getType() {
        // Return the registered particle type
    }
}
```

We then use this `ParticleOptions` implementation in our custom `ParticleType`...

```java
public class MyParticleType extends ParticleType<MyParticleOptions> {
    // The boolean parameter again determines whether to limit particles at lower particle settings.
    // See implementation of the MyParticleTypes class near the top of the article for more information.
    public MyParticleType(boolean overrideLimiter) {
        // Pass the deserializer to super.
        super(overrideLimiter);
    }

    @Override
    public MapCodec<MyParticleOptions> codec() {
        return MyParticleOptions.CODEC;
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, MyParticleOptions> streamCodec() {
        return MyParticleOptions.STREAM_CODEC;
    }
}
```

... and reference it during [registration][registry]:

```java
public static final Supplier<MyParticleType> MY_CUSTOM_PARTICLE = PARTICLE_TYPES.register(
    "my_custom_particle",
    () -> new MyParticleType(false)
);
```

The registered particle is then passed into `ParticleOptions#getType`:

```java
public class MyParticleOptions implements ParticleOptions {
    
    // ...

    @Override
    public ParticleType<?> getType() {
        return MY_CUSTOM_PARTICLE.get();
    }
}
```

## Particle Descriptions

Particle descriptions are JSON files in the `assets/<namespace>/particles` directory. A particle description has the same name as its associated [particle type][particletype], and consists of a list of textures relative to `assets/<namespace>/textures/particles`.

A particle description looks something like this:

```json5
{
    // A list of textures that will be played in order. Will loop if necessary.
    // Texture locations are relative to the textures/particle folder.
    "textures": [
        // Points to `assets/examplemod/textures/particle/my_particle_0.png`
        "examplemod:my_particle_0",
        "examplemod:my_particle_1",
        "examplemod:my_particle_2",
        "examplemod:my_particle_3"
    ]
}
```

During resource reload, the `ParticleResources` loads all particle descriptions and stitches the textures into the `TextureAtlas#LOCATION_PARTICLES` atlas. Then, a `SpriteSet` is created for each description, containing a list of the specified `TextureAtlasSprite`s.

### Using the Description

To allow a [particle] to make use of its description, the `ParticleType` must be associated with a [`ParticleProvider`][provider] that takes in the `SpriteSet` using the [client-side][side] [mod bus][modbus] [event] `RegisterParticleProvidersEvent`:

```java
public class MyParticleProvider implements ParticleProvider<SimpleParticleType> {

    private final SpriteSet spriteSet;

    // Take in the sprite set provided by the `ParticleResources`.
    public MyParticleProvider(SpriteSet spriteSet) {
        this.spriteSet = spriteSet;
    }

    // ...
}

// In some client-only event handler

@SubscribeEvent // on the mod event bus only on the physical client
public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
    // #registerSpriteSet MUST be used when dealing with particle descriptions.
    event.registerSpriteSet(MyParticleTypes.MY_PARTICLE.get(), MyParticleProvider::new);
}
```

:::warning
If a particle description is created for a particle type with no associated `ParticleProvider` via `RegisterParticleProvidersEvent#registerSpriteSet`, then a 'Redundant texture list' message will be logged.
:::

### Datagen

Particle definition files can also be [datagenned][datagen] by extending `ParticleDescriptionProvider` and overriding the `#addDescriptions()` method:

```java
public class MyParticleDescriptionProvider extends ParticleDescriptionProvider {
    // Get the parameters from `GatherDataEvent.Client`.
    public MyParticleDescriptionProvider(PackOutput output) {
        super(output);
    }

    // Assumes that all the referenced particles actually exists. Replace "examplemod" with your mod id.
    @Override
    protected void addDescriptions() {
        // Adds a single sprite particle definition with the file at
        // assets/examplemod/textures/particle/my_single_particle.png.
        spriteSet(MyParticleTypes.MY_SINGLE_PARTICLE.get(), Identifier.fromNamespaceAndPath("examplemod", "my_single_particle"));
        // Adds a multi sprite particle definition, with a vararg parameter. Alternatively accepts an iterable.
        spriteSet(MyParticleTypes.MY_MULTI_PARTICLE.get(),
            Identifier.fromNamespaceAndPath("examplemod", "my_multi_particle_0"),
            Identifier.fromNamespaceAndPath("examplemod", "my_multi_particle_1"),
            Identifier.fromNamespaceAndPath("examplemod", "my_multi_particle_2")
        );
        // Alternative for the above, appends "_<index>" to the base name given, for the given amount of textures.
        spriteSet(MyParticleTypes.MY_ALT_MULTI_PARTICLE.get(),
            // The base name.
            Identifier.fromNamespaceAndPath("examplemod", "my_multi_particle"),
            // The number of textures.
            3,
            // Whether to reverse the list, i.e. start at the last element instead of the first.
            false
        );
    }
}
```

Don't forget to add the provider to the `GatherDataEvent.Client`:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createProvider(MyParticleDescriptionProvider::new);
}
```

## Spawning Particles

As a reminder from before, the server only knows [`ParticleType`s][particletype] and [`ParticleOption`s][options], while the client works directly with `Particle`s provided by `ParticleProvider`s that are associated with a `ParticleType`. Consequently, the ways in which particles are spawned are vastly different depending on the side you are on.

- **Common code**: Call `Level#addParticle` or `Level#addAlwaysVisibleParticle`. This is the preferred way of creating particles that are visible to everyone.
- **Client code**: Use the common code way. Alternatively, create a `new Particle()` with the particle class of your choice and call `Minecraft.getInstance().particleEngine#add(Particle)` with that particle. Note that particles added this way will only display for the client and thus not be visible to other players.
- **Server code**: Call `ServerLevel#sendParticles`. Used in vanilla by the `/particle` command.

[clientparticle]: ../../rendering/particles.md
[datagen]: ../index.md#data-generation
[event]: ../../concepts/events.md
[modbus]: ../../concepts/events.md#event-buses
[options]: #custom-particletypes
[particle]: ../../rendering/particles.md
[particletype]: #registering-particletypes
[provider]: ../../rendering/particles.md#particleprovider
[side]: ../../concepts/sides.md

## resources/client/sounds

# Sounds

Sounds, while not required for anything, can make a mod feel much more nuanced and alive. Minecraft offers you various ways to register and play sounds, which will be laid out in this article.

## Terminology

The Minecraft sound engine uses a variety of terms to refer to different things:

- **Sound event**: A sound event is an in-code trigger that tells the sound engine to play a certain sound. `SoundEvent`s are also the things you register to the game.
- **Sound category** or **sound source**: Sound categories are rough groupings of sounds that can be individually toggled. The sliders in the sound options GUI represent these categories: `master`, `block`, `player` etc. In code, they can be found in the `SoundSource` enum.
- **Sound definition**: A mapping of a sound event to one or multiple sound objects, plus some optional metadata. Sound definitions are located in a namespace's [`sounds.json` file][soundsjson].
- **Sound object**: A JSON object consisting of a sound file location, plus some optional metadata.
- **Sound file**: An on-disk sound file. Minecraft only supports `.ogg` sound files.

:::danger
Due to the implementation of OpenAL (Minecraft's audio library), for your sound to have attenuation - that is, for it to get quieter and louder depending on the player's distance to it -, your sound file must be mono (single channel). Stereo (multichannel) sound files will not be subject to attenuation and always play at the player's location, making them ideal for ambient sounds and background music. See also [MC-146721][bug].
:::

## Creating `SoundEvent`s

`SoundEvent`s are [registered objects][registration], meaning that they must be registered to the game through a `DeferredRegister` and be singletons:

```java
public class MySoundsClass {
    // Assuming that your mod id is examplemod
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, "examplemod");
    
    // All vanilla sounds use variable range events.
    public static final Holder<SoundEvent> MY_SOUND = SOUND_EVENTS.register(
            "my_sound",
            // Takes in the registry name
            SoundEvent::createVariableRangeEvent
    );
    
    // There is a currently unused method to register fixed range (= non-attenuating) events as well:
    public static final Holder<SoundEvent> MY_FIXED_SOUND = SOUND_EVENTS.register(
            "my_fixed_sound",
            // 16 is the default range of sounds. Be aware that due to OpenAL limitations,
            // values above 16 have no effect and will be capped to 16.
            registryName -> SoundEvent.createFixedRangeEvent(registryName, 16f)
    );
}
```

Of course, don't forget to add your registry to the [mod event bus][modbus] in the [mod constructor][modctor]:

```java
public ExampleMod(IEventBus modBus) {
    MySoundsClass.SOUND_EVENTS.register(modBus);
    // other things here
}
```

And voilà, you have a sound event!

## `sounds.json`

_See also: [sounds.json][mcwikisounds] on the [Minecraft Wiki][mcwiki]_

Now, to connect your sound event to actual sound files, we need to create sound definitions. All sound definitions for a namespace are stored in a single file named `sounds.json`, also known as the sound definitions file, directly in the namespace's root. Every sound definition is a mapping of sound event id (e.g. `my_sound`) to a JSON sound object. Note that the sound event ids do not specify a namespace, as that is already determined by the namespace the sound definitions file is in. An example `sounds.json` would look something like this:

```json5
{
    // Sound definition for the sound event "examplemod:my_sound"
    "my_sound": {
        // List of sound objects. If this contains more than one element, an element will be chosen randomly.
        "sounds": [
            // Only name is required, all other properties are optional.
            {
                // Location of the sound file, relative to the namespace's sounds folder.
                // This example references a sound at assets/examplemod/sounds/sound_1.ogg.
                "name": "examplemod:sound_1",
                // May be "sound" or "event". "sound" causes the name to refer to a sound file.
                // "event" causes the name to refer to another sound event. Defaults to "sound".
                "type": "sound",
                // The volume this sound will be played at. Must be between 0.0 and 1.0 (default).
                "volume": 0.8,
                // The pitch value the sound will be played at.
                // Must be between 0.0 and 2.0. Defaults to 1.0.
                "pitch": 1.1,
                // Weight of this sound when choosing a sound from the sounds list. Defaults to 1.
                "weight": 3,
                // If true, the sound will be streamed from the file instead of loaded all at once.
                // Recommended for sound files that are more than a few seconds long. Defaults to false.
                "stream": true,
                // Manual override for the attenuation distance. Defaults to 16. Ignored by fixed range sound events.
                "attenuation_distance": 8,
                // If true, the sound will be loaded into memory on pack load, instead of when the sound is played.
                // Vanilla uses this for underwater ambience sounds. Defaults to false.
                "preload": true
            },
            // Shortcut for { "name": "examplemod:sound_2" }
            "examplemod:sound_2"
        ]
    },
    "my_fixed_sound": {
        // Optional. If true, replaces sounds from other resource packs instead of adding to them.
        // See the Merging chapter below for more information.
        "replace": true,
        // The translation key of the subtitle displayed when this sound event is triggered.
        "subtitle": "examplemod.my_fixed_sound",
        "sounds": [
            "examplemod:sound_1",
            "examplemod:sound_2"
        ]
    }
}
```

### Merging

Unlike most other resource files, `sounds.json` do not overwrite values in packs below them. Instead, they are merged together and then interpreted as one combined `sounds.json` file. Consider sounds `sound_1`, `sound_2`, `sound_3` and `sound_4` being defined in two `sounds.json` files from two different resource packs RP1 and RP2, where RP2 is placed below RP1:

`sounds.json` in RP1:

```json5
{
    "sound_1": {
        "sounds": [
            "sound_1"
        ]
    },
    "sound_2": {
        "replace": true,
        "sounds": [
            "sound_2"
        ]
    },
    "sound_3": {
        "sounds": [
            "sound_3"
        ]
    },
    "sound_4": {
        "replace": true,
        "sounds": [
            "sound_4"
        ]
    }
}
```

`sounds.json` in RP2:

```json5
{
    "sound_1": {
        "sounds": [
            "sound_5"
        ]
    },
    "sound_2": {
        "sounds": [
            "sound_6"
        ]
    },
    "sound_3": {
        "replace": true,
        "sounds": [
            "sound_7"
        ]
    },
    "sound_4": {
        "replace": true,
        "sounds": [
            "sound_8"
        ]
    }
}
```

The combined (merged) `sounds.json` file the game would then go on and use to load sounds would look something look this (only in memory, this file is never written anywhere):

```json5
{
    "sound_1": {
        // replace false and false: add from lower pack, then from upper pack
        "sounds": [
            "sound_5",
            "sound_1"
        ]
    },
    "sound_2": {
        // replace true in upper pack and false in lower pack: add from upper pack only
        "sounds": [
            "sound_2"
        ]
    },
    "sound_3": {
        // replace false in upper pack and true in lower pack: add from lower pack, then from upper pack
        // Would still discard values from a third resource pack sitting below RP2
        "sounds": [
            "sound_7",
            "sound_3"
        ]
    },
    "sound_4": {
        // replace true and true: add from upper pack only
        "sounds": [
            "sound_8"
        ]
    }
}
```

## Playing Sounds

Minecraft offers various methods to play sounds, and it is sometimes unclear which one should be used. All methods accept a `SoundEvent`, which can either be your own or a vanilla one (vanilla sound events are found in the `SoundEvents` class). For the following method descriptions, client and server refer to the [logical client and logical server][sides], respectively.

### `Level`

- `playSeededSound(Entity entity, double x, double y, double z, Holder<SoundEvent> soundEvent, SoundSource soundSource, float volume, float pitch, long seed)`
    - Client behavior: If the player passed in is the local player, play the sound event to the player at the given location, otherwise no-op.
    - Server behavior: A packet instructing the client to play the sound event to the player at the given location is sent to all players except the one passed in.
    - Usage: Call from client-initiated code that will run on both sides. The server not playing it to the initiating player prevents playing the sound event twice to them. Alternatively, call from server-initiated code (e.g. a [block entity][be]) with a `null` player to play the sound to everyone.
- `playSound(Entity entity, double x, double y, double z, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch)`
    - Forwards to `playSeededSound` with a random seed selected and the holder wrapped around the `SoundEvent`
- `playSound(Entity entity, BlockPos pos, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch)`
    - Forwards to the above method with `x`, `y` and `z` taking the values of `pos.getX() + 0.5`, `pos.getY() + 0.5` and `pos.getZ() + 0.5`, respectively.
- `playLocalSound(double x, double y, double z, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch, boolean distanceDelay)`
    - Client behavior: Plays the sound to the player at the given location. Does not send anything to the server. If `distanceDelay` is `true`, delays the sound based on the distance to the player.
    - Server behavior: No-op.
    - Usage: Called from custom packets sent from the server. Vanilla uses this for thunder sounds.
- `playPlayerSound(SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch)`
    - Client behavior: Plays the sound that is bound to the player's location. Does not send anything to the server.
    - Server behavior: No-op.
    - Usage: Vanilla uses this for ambient block sounds.

### `ClientLevel`

- `playLocalSound(BlockPos pos, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch, boolean distanceDelay)`
    - Forwards to `Level#playLocalSound` with `x`, `y` and `z` taking the values of `pos.getX() + 0.5`, `pos.getY() + 0.5` and `pos.getZ() + 0.5`, respectively.

### `Entity`

- `playSound(SoundEvent soundEvent, float volume, float pitch)`
    - Forwards to `Level#playSound` with `null` as the player, `Entity#getSoundSource` as the sound source, the entity's position for x/y/z, and the other parameters passed in.

### `Player`

- `playSound(SoundEvent soundEvent, float volume, float pitch)` (overrides the method in `Entity`)
    - Forwards to `Level#playSound` with `this` as the player, `SoundSource.PLAYER` as the sound source, the player's position for x/y/z, and the other parameters passed in. As such, the client/server behavior mimics the one from `Level#playSound`:
        - Client behavior: Play the sound event to the client player at the given location.
        - Server behavior: Play the sound event to everyone near the given location except the player this method was called on.

## Datagen

Sound files themselves can of course not be [datagenned][datagen], but `sounds.json` files can. To do so, we extend `SoundDefinitionsProvider` and override the `registerSounds()` method:

```java
public class MySoundDefinitionsProvider extends SoundDefinitionsProvider {
    // Parameters can be obtained from `GatherDataEvent.Client`.
    public MySoundDefinitionsProvider(PackOutput output) {
        // Use your actual mod id instead of "examplemod".
        super(output, "examplemod");
    }

    @Override
    public void registerSounds() {
        // Accepts a Holder<SoundEvent>, a SoundEvent, or a Identifier as the first parameter.
        add(MySoundsClass.MY_SOUND, SoundDefinition.definition()
            // Add sound objects to the sound definition. Parameter is a vararg.
            .with(
                // Accepts either a string or a Identifier as the first parameter.
                // The second parameter can be either SOUND or EVENT, and can be omitted if the former.
                sound("examplemod:sound_1", SoundDefinition.SoundType.SOUND)
                    // Sets the volume. Also has a double counterpart.
                    .volume(0.8f)
                    // Sets the pitch. Also has a double counterpart.
                    .pitch(1.2f)
                    // Sets the weight.
                    .weight(2)
                    // Sets the attenuation distance.
                    .attenuationDistance(8)
                    // Enables streaming.
                    // Also has a parameterless overload that defers to stream(true).
                    .stream(true)
                    // Enables preloading.
                    // Also has a parameterless overload that defers to preload(true).
                    .preload(true),
                // The shortest we can get.
                sound("examplemod:sound_2")
            )
            // Sets the subtitle.
            .subtitle("sound.examplemod.sound_1")
            // Enables replacing.
            .replace(true)
        );
    }
}
```

As with every data provider, don't forget to register the provider to the event:

```java
@SubscribeEvent // on the mod event bus
public static void gatherData(GatherDataEvent.Client event) {
    event.createProvider(MySoundDefinitionsProvider::new);
}
```

[bug]: https://bugs.mojang.com/browse/MC-146721
[datagen]: ../index.md#data-generation
[mcwiki]: https://minecraft.wiki
[mcwikisounds]: https://minecraft.wiki/w/Sounds.json
[modbus]: ../../concepts/events.md#event-buses
[modctor]: ../../gettingstarted/modfiles.md#javafml-and-mod
[registration]: ../../concepts/registries.md
[sides]: ../../concepts/sides.md#the-logical-side
[soundsjson]: #soundsjson

## resources/client/textures

# Textures

All textures in Minecraft are PNG files located within a namespace's `textures` folder. JPG, GIF and other image formats are not supported. The path of [identifiers] referring to textures is generally relative to the `textures` folder, so for example, the identifier `examplemod:block/example_block` refers to the texture file at `assets/examplemod/textures/block/example_block.png`.

Textures should generally be in sizes that are powers of two, for example 16x16 or 32x32. Unlike older versions, modern Minecraft natively supports block and item texture sizes greater than 16x16. For textures that are not in powers of two that you render yourself anyway (for example GUI backgrounds), create an empty file in the next available power-of-two size (often 256x256), and add your texture in the top left corner of that file, leaving the rest of the file empty. The actual size of the drawn texture can then be set in the code that uses the texture.

## Texture Metadata

Texture metadata can be specified in a file named exactly the same as the texture, with an additional `.mcmeta` suffix. For example, an animated texture at `textures/block/example.png` would need an accompanying `textures/block/example.png.mcmeta` file. The `.mcmeta` file has the following format (all optional):

```json5
{
    // Metadata for a general texture
    "texture": {
        // Whether the texture will be blurred if needed. Defaults to false.
        // Currently specified by the codec, but unused otherwise both in the files and in code.
        "blur": true,
        // Whether the texture will be clamped if needed. Defaults to false.
        // Currently specified by the codec, but unused otherwise both in the files and in code.
        "clamp": true,
        // Sets the strategy used when generating mipmaps (lower resolutions of textures used at
        // a distance).
        // Can either be:
        // - `mean`: The default that averages the color between four pixels.
        // - `cutout`: 'mean', except that all levels are generated from the original texture
        // rather than the close mipmap, with alpha value snapped to 0 or 1 using a threshold
        // of 0.2.
        // - `strict_cutout`: 'cutout', except the alpha value snaps using a threshold of 0.6.
        // - `dark_cutout`: 'mean', except that the surrounding pixels are only included in the
        // average if their alpha is not 0.
        "mipmap_strategy": "mean",
        // Offsets the cutoff alpha when determining whether a pixel should be made either fully
        // opaque or transparent for mipmaps. For example, setting to 0.3 with the 'cutout' strategy
        // changes the alpha value snap to 0.2 + 0.3 = 0.5.
        "alpha_cutoff_bias": 0.3
    },

    // Metadata for a texture used as a gui sprite
    "gui": {
        // Specifies how the texture will be scaled if needed. Can be one of these three:
        "scaling": {
            "type": "stretch" // default
        },
        "scaling": {
            "type": "tile",
            "width": 16,
            "height": 16
        },
        "scaling": {
            // Like "tile", but allows specifying the border offsets.
            "type": "nine_slice",
            "width": 16,
            "height": 16,
            // May also be a single int that is used as the value for all four sides.
            "border": {
                "left": 0,
                "top": 0,
                "right": 0,
                "bottom": 0
            },
            // When true the center part of the texture will be applied like
            // the stretch type instead of a nine slice tiling.
            "stretch_inner": true
        }
    },

    // Metadata for an animated texture
    // See below
    "animation": {}
}
```

## Animated Textures

Minecraft natively supports animated textures for blocks and items. Animated textures consist of a texture file where the different animation stages are located below each other (for example, an animated 16x16 texture with 8 phases would be represented through a 16x128 PNG file).

To actually be animated and not just be displayed as a distorted texture, there must be an `animation` object in the texture metadata. The sub-object can be empty, but may contain the following optional entries:

```json5
{
    "animation": {
        // A custom order in which the frames are played. If omitted, the frames are played top to bottom.
        "frames": [1, 0],
        // How long one frame stays before switching to the next animation stage, in frames. Defaults to 1.
        "frametime": 5,
        // Whether to interpolate between animation stages. Defaults to false.
        "interpolate": true,
        // Width and height of one animation stage. If omitted, uses the texture width for both of these.
        "width": 12,
        "height": 12
    }
}
```

[identifiers]: ../../misc/identifier.md