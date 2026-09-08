# 物品与组件(本地知识库)

> 概念与用法看这里;具体某个物品的实时属性用 `inspect_item` 工具查(准);给玩家带属性的物品用 `give` 的组件语法(见指令文档)。

## 数据组件(1.20.5+ 物品属性体系)
物品的所有属性都由"数据组件"描述,`/give` 用方括号组件语法覆盖:
`物品id[组件1=值,组件2=值]`。常用组件:

- `custom_name='"名字"'`(注意:JSON 文本要引号套引号)
- `lore=['{"text":"第一行","color":"gray"}','{"text":"第二行"}']`
- `enchantments='{levels:{"minecraft:sharpness":5,"minecraft:unbreaking":3}}'`
- `stored_enchantments='{levels:{...}}'`(附魔书)
- `unbreakable={}` 不可破坏;`damage=<n>` 已损耗耐久;`max_damage=<n>` 最大耐久
- `attribute_modifiers=[{type:"minecraft:generic.attack_damage",amount:5,operation:"add_value",slot:"mainhand"}]`
- `food={nutrition:8,saturation:0.8}`(改食物回复)
- `tool={rules:[{blocks:"#minecraft:mineable/pickaxe",speed:20,correct_for_drops:true}]}`
- `fire_resistant={}` 防火(岩浆不烧);`max_stack_size=<n>` 改堆叠上限
- `rarity="minecraft:epic"` 稀有度(common/uncommon/rare/epic)
- `potion_contents={potion:"minecraft:strong_healing"}` 药水
- `item_name='"显示名"'`(区别于 custom_name:不可被铁砧改)
- `enchantment_glint_override=true` 强制附魔光效

## 附魔速查(常用,1.21 id)
保护 protection、火焰防护 fire_protection、爆炸保护 blast_protection、弹射物保护 projectile_protection、
锋利 sharpness、亡灵杀手 smite、节肢杀手 bane_of_arthropods、击退 knockback、火焰附加 fire_aspect、
掠夺 looting、效率 efficiency、精准采集 silk_touch、时运 fortune、力量 power、冲击 punch、
火矢 flame、无限 infinity、耐久 unbreaking、经验修补 mending、精准/时运互斥、无限与 muffler(多重箭)互斥。
水下呼吸 respiration、水下速掘 aqua_affinity、深海探索者 depth_strider、绑定灵魂 binding_curse、
灵魂疾行 soul_speed、迅捷潜行 swift_sneak、 冰霜行者 frost_walker、引雷 channeling、激流 riptide、
忠诚 loyalty、多重箭 multishot、穿透 piercing、快速装填 quick_charge。

## 耐久与工具
- 工具/护甲有 max_damage;损耗到 0 损毁;`unbreakable` 组件可免疫损耗;
- 挖掘等级:wooden(1)→stone(2)→iron(3)→diamond(4)→netherite(5),低级工具挖不动高级矿;
- 速度:木质 2、石质 4、铁质 6、钻石 8、下界合金 9;金质速度 12 但耐久低;
- 可燃物参考:煤 8 件、木板 1.5 件、岩浆桶 100 件(熔炉燃料)。

## 食物(基础值,饱食度=饥饿点+饱和度)
面包 5、熟牛肉 8(饱和 12.8)、金苹果 4+吸收/再生效果、附魔金苹果(更强)、
生猪排 3(生食可能食物中毒)、腐肉 4(80% 饥饿 debuff)、蛋糕(放置型,7 块)。

## 常见物品行为
- 未成年的动物不能繁殖;牛/羊/猪/鸡吃对应食物进繁殖模式(爱心粒子);
- 铁傀儡由 4 铁块+雕刻南瓜搭建;雪傀儡 2 雪+南瓜(怕热/水);
- 下界合金锭 = 4 下界合金碎片(+1 金锭,锻造台+锻造模板 netherite_upgrade_smithing_template);
- 附魔台周围 15 格书架 = 满级 30 级附魔;
- 铁砧合并两把同类型工具耐久与附魔,改名,经验消耗随次数增加;
- 磨石:去附魔(返还部分经验);砂轮:修复+合并(去附魔);
- 织布机:旗帜图案;锻造台:下界合金升级/盔甲纹饰。

## 常见 id 前缀与命名空间
- 原版 = `minecraft:`;模组各有命名空间(如 mcphone:、waystones:);
- 不确定 id 时先用 `list_items` 工具搜,再 inspect_item 看详情。

## 获取方式的常规规律
- 矿物:煤(任何石头层)、铁(较高处常见,64 层附近最多)、铜(中等高度)、
  金(下层/恶地)、钻石(深层,约 -59 附近最富,岩浆湖边小心)、青金石(深层);
- 下界:远古残骸(下层,爆炸抗性,1 块=1 碎片,4 碎片+金=合金);
- 末地:紫颂果(紫颂植物)、鞘翅(末地船)、龙蛋;
- 结构战利品:村庄箱子(面包/铁/绿宝石)、沉船(藏宝图)、遗迹(海洋之心)。
