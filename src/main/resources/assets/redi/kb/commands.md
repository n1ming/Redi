# MC 原版指令速查(本地知识库)

> 语法约定:`<必填>` `[可选]` `(...)`二选一;坐标支持 `~ ~ ~`(相对)/`^ ^ ^`(视线相对);
> 目标选择器见《选择器与坐标》文档。需要某条指令在当前服务器的精确语法时,调用 `command_usage` 工具实时查询(对模组指令同样有效)。

## gamemode
`gamemode <survival|creative|adventure|spectator> [玩家]`
改游戏模式。省略玩家=自己。改别人需要 OP。

## give
`give <玩家> <物品id> [数量]`
给予物品。1.20.5+ 支持组件语法直接给带 NBT 的物品:
`give @s diamond_sword[custom_name='"屠龙刀"',enchantments='{levels:{"minecraft:sharpness":10}}']`
`give @s iron_pickaxe[unbreakable={},enchantments='{levels:{"minecraft:efficiency":5}}']`
常用组件:custom_name、lore(列表)、enchantments(levels 映射)、unbreakable({})、
attribute_modifiers、food、max_damage、item_model。详见《物品与组件》文档。

## tp / teleport
`teleport <目标> <实体>`(把前者传到后者处)
`teleport <目标> <x> <y> <z> [朝向y角度 x角度]`
传送。`/tp @s ~ ~10 ~` 向上传 10 格;`/tp @s ^ ^ ^5` 沿视线向前 5 格。

## locate
`locate structure <结构id或#标签>` / `locate biome <群系id>` / `locate poi <poi类型>`
返回最近结构的坐标(以聊天反馈文本形式回传,例如 "The nearest Village is at [123, 70, -456]")。
常用结构 id:village、mansion、monument、stronghold、ancient_city、trial_chambers、
ruined_portal、mineshaft、desert_pyramid、jungle_pyramid、igloo、swamp_hut、outpost、fortress、bastion_remnant、end_city。

## effect
`effect give <目标> <效果id> [秒数] [倍率] [隐藏粒子]`
`effect clear <目标> [效果id]`
给/清状态效果。例:`effect give @s minecraft:speed 60 2` 速度II 60 秒;秒数 0 或 clear=清除。
常用:speed、haste、strength、jump_boost、regeneration、resistance、fire_resistance、
water_breathing、invisibility、night_vision、levitation、glowing、saturation。

## enchant
`enchant <目标> <附魔id> [等级]`
给手中物品加附魔(受正常附魔限制约束;绕过限制用 give 的 enchantments 组件)。

## summon
`summon <实体id> [坐标] [NBT]`
召唤实体。例:`summon minecraft:lightning_bolt ~ ~ ~`、
`summon minecraft:villager ~ ~ ~ {VillagerData:{profession:"minecraft:farmer"}}`。

## kill
`kill [目标]` 杀死目标实体。`/kill @e[type=!minecraft:player]` 清掉周围所有非玩家实体(慎用)。

## clear
`clear [目标] [物品id] [最大数量]`
清空背包物品。`/clear @s` 清空自己全部;`/clear @s minecraft:rotten_flesh` 只清腐肉。

## give 相关:item(修改已存在物品)
`item modify block/entity ...`(用数据包 modifier,较复杂,优先 give+组件)。

## setblock
`setblock <x> <y> <z> <方块id> [destroy|keep|replace]`
放置/替换单个方块。例:`setblock ~ ~-1 ~ minecraft:diamond_block`。

## fill
`fill <x1> <y1> <z1> <x2> <y2> <z2> <方块id> [destroy|hollow|keep|outline|replace <过滤方块>]`
批量填充区域(最多 32768 格)。

## clone
`clone <起x y z> <止x y z> <目标x y z> [replace|masked|filtered...]` 复制区域。

## time
`time set <day|noon|night|midnight|数值>` / `time add <数值>`
调整时间。day=1000、noon=6000、night=13000、midnight=18000(一天 24000 tick)。

## weather
`weather <clear|rain|thunder> [持续时间秒]`

## difficulty
`difficulty <peaceful|easy|normal|hard>`

## gamerule
`gamerule <规则名> [值]`
常用:keepInventory(true 死亡不掉落)、doDaylightCycle(时间流逝)、
doMobSpawning(刷怪)、doWeatherCycle、mobGriefing(苦力怕/末影人破坏)、
sendCommandFeedback(指令反馈显示)、randomTickSpeed(作物/火焰随机刻速度)。

## xp / experience
`experience add <目标> <数量> [points|levels]`
`experience set <目标> <数量> [points|levels]`
给/设经验。`/xp add @s 10 levels` 加 10 级。

## effect 补充:attribute
`attribute <目标> minecraft:<属性> [base|modifier ...]`
查看/修改属性。例:`attribute @s minecraft:generic.max_health base set 40`(最大生命 40)。
常用属性:generic.max_health、generic.movement_speed、generic.attack_damage、
generic.armor、generic.luck(1.21 中部分属性 id 改为无 generic. 前缀,如 max_health;
以 command_usage 实时查询为准)。

## title
`title <目标> <title|subtitle|actionbar|clear|times> [文本]`
大标题/副标题/物品栏上方动作栏文字。JSON 文本组件格式。

## tellraw
`tellraw <目标> <JSON文本组件>`
发送富文本(支持颜色/点击/悬停)。例:
`tellraw @a {"text":"你好","color":"gold","bold":true}`

## say / me / msg
`say <文本>`(广播,带玩家名)/ `me <动作文本>`(/me 第三人称动作)/
`msg <玩家> <文本>`(私聊,别名 /w、/tell)。

## playasound → playsound
`playsound <声音id> <master|music|record|weather|block|hostile|neutral|player|ambient|voice> <目标> [坐标] [音量] [音调] [最小音量]`
播放声音。例:`playsound minecraft:entity.experience_orb.pickup player @s`。

## particle
`particle <粒子id> [坐标] [扩散xyz] [速度] [数量]`
`particle minecraft:heart ~ ~1 ~ 0.5 0.5 0.5 0 5`

## spawnpoint / setworldspawn
`spawnpoint [目标] [坐标] [角度]` 设置个人重生点;`setworldspawn [坐标]` 设置世界出生点。

## seed
`seed` 显示世界种子。

## list
`list` 列出在线玩家。

## help
`help [指令]` 服务端自带帮助。

## advancement
`advancement give|revoke <目标> everything|only <进度id> [criterion]`
给/撤销进度。`/advancement give @s only minecraft:end/kill_dragon`。

## recipe
`recipe give|take <目标> <配方id或*>` 解锁/收回配方。

## reload
`reload` 重载数据包。

## datapack
`datapack list|enable|disable ...` 管理数据包。

## scoreboard
`scoreboard objectives add|list|remove ...`、`scoreboard players add|set|get|list ...`
计分板系统(做统计/小游戏用)。

## tag
`tag <实体> add|remove|list <标签>` 给实体打标签(配合选择器 tag= 过滤)。

## team
`team add|join|leave|empty|list ...` 队伍系统。

## bossbar
`bossbar add|remove|get|set ...` 自定义 Boss 血条。

## execute(最强大的复合指令)
基础形式:`execute as <实体> at <实体> positioned <坐标> rotated <角度> run <指令>`
- `as @e[type=cow,limit=1] at @s run tp @s ~ ~5 ~`:把第一头牛上传 5 格;
- `execute if entity @e[type=minecraft:creeper,distance=..10] run say 有苦力怕!`;
- `execute if block ~ ~-1 ~ minecraft:diamond_block run say 脚下是钻石块`;
- `execute positioned 100 64 -200 run locate structure village`(以指定位置为原点执行)。
常用子句:as/at/at positioned/rotated/facing/if(if block|entity|score|predicate)/
store(把结果写进 score 或方块)/run。store 例:
`execute store result score @s tmp run locate structure village`(把 locate 结果写入分数,可再读出)。

## loot
`loot give <目标> loot <战利品表id>` 按战利品表直接给物品。
例:`loot give @s loot minecraft:blocks/diamond_ore`。

## place(1.20+)
`place template <结构模板id> [坐标]` 放置结构模板;`place jigsaw ...`。

## damage(1.19.4+)
`damage <目标> <数值> [伤害类型] [at|by ...]` 对实体造成伤害。

## ride(1.20+)
`ride <目标> mount <坐骑>` / `ride <目标> dismount` 骑乘控制。

## rotate(1.20.5+)
`rotate <目标> <角度>` / `rotate <目标> facing <坐标|实体>` 设置朝向。

## 频道与私聊别名
/w、/tell = msg;/tm = teammsg。

## 服务器管理类(本助手禁用,仅列出)
ban、ban-ip、banlist、pardon、pardon-ip、op、deop、kick、whitelist、stop、
save-all、save-on、save-off、setidletimeout、jfr。
