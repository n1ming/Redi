# 选择器与坐标(本地知识库)

## 目标选择器
- `@p` 最近的玩家;`@r` 随机玩家;`@a` 所有玩家;`@e` 所有实体;`@s` 执行者自己;`@n` 最近实体(可以是非玩家,1.20.5+)。
- `@e[type=minecraft:cow,limit=3,sort=nearest]`:最近的三头牛。
- 选择器参数(可组合,空格分隔多个条件用 `,`):
  - `type=<id>` 实体类型;`type=!<id>` 排除;`type=#minecraft:tag` 标签;
  - `name=<名字>`(JSON 文本);`tag=<标签>` / `tag=!<标签>`;
  - `distance=..10`(10 格内)、`distance=5..20`(5 到 20 格);
  - `x,y,z`+`dx,dy,dz` 立方体范围:`x=100,y=64,z=-200,dx=10,dy=5,dz=10`;
  - `level=10..30` 经验等级;`gamemode=creative`;`team=<队>`;
  - `limit=<n>` 数量上限;`sort=nearest|furthest|random|arbitrary`;
  - `scores={ Kills=1.. }` 计分板过滤;`nbt={...}` NBT 过滤(较慢);
  - `predicate=<谓词id>` 数据包谓词;`x_rotation=-90..90`、`y_rotation=...` 视角过滤。

## 坐标
- 绝对坐标:`100 64 -200`;
- 相对坐标:`~ ~ ~` = 当前位置;`~-1 ~ ~`=向西 1 格;`~ ~3 ~`=头顶上方;
- 视线相对坐标:`^ ^ ^5` = 沿视线向前 5 格;`^1 ^ ^0` = 视线左侧 1 格;
- 朝向角:y 角(水平,0=南,+90=西,±180=北,-90=东)、x 角(垂直,0=水平,-90=正上方,+90=正下方)。

## 实体/方块朝向与视角
- 获取准星指向:本助手优先用 get_target 工具(不需要玩家自己解析);
- `execute rotated as @e[type=armor_stand,limit=1] positioned as @s run ...` 可借用他人视角。

## JSON 文本组件(tellraw/title 常用)
- 最简:`{"text":"你好"}`;
- 颜色:`{"text":"金句","color":"gold","bold":true,"italic":false}`;
- 颜色名:white,gray,dark_gray,black,yellow,gold,light_purple,dark_purple,aqua,dark_aqua,red,dark_red,green,dark_green,blue,dark_blue;
- 点击/悬停:`{"text":"点我","clickEvent":{"action":"run_command","value":"/say hi"},"hoverEvent":{"action":"show_text","contents":"提示"}}`;
- 附加:`{"extra":[{"text":"A","color":"red"},{"text":"B","color":"aqua"}]}`;
- 1.21.5+ 新 SNBT 格式:`"你好"` 或 `{text:"你好",color:"gold"}`(兼容以服务端为准,优先旧 JSON 格式)。
