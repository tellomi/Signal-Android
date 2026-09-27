# 插画许可（CC0，不属于 AGPL）

本模块里下面这些文件是插画，**来自 [Open Doodles](https://www.opendoodles.com/)（作者 Pablo Stanley），按 CC0 1.0（公有领域贡献）使用**，
不属于本仓库的 AGPL-3.0 授权范围：

| 文件（浅色在 `res/drawable/`，深色在 `res/drawable-night/`） | Open Doodles 原图 | 用在哪 |
| --- | --- | --- |
| `tellomi_welcome_swinging.xml` | A person on a giant swing | 开屏轮播第 1 张 |
| `tellomi_welcome_selfie.xml` | A person taking a selfie | 开屏轮播第 2 张 |
| `tellomi_welcome_loving.xml` | A person holding a heart | 开屏轮播第 3 张 |
| `tellomi_welcome_float.xml` | A person floating in mid air | 开屏轮播第 4 张 |

来源：Tellomi 超级仓库 `docs/brand/illustrations/<名字>-{light,dark}.svg`（由 `scripts/brand/doodle.py` 从 Open Doodles 原图改成纯黑白），
转成 VectorDrawable。超过 aapt 字符串上限的长路径改成了相对坐标，并按 x 切成几条、用 clip-path 裁开，画出来和原图一致。

CC0 不要求署名，这里写明来源只是为了说清楚这些图不按 AGPL 授权。CC0 原文：<https://creativecommons.org/publicdomain/zero/1.0/>。

# Illustration license (CC0, not AGPL)

The files listed above are illustrations from [Open Doodles](https://www.opendoodles.com/) by Pablo Stanley, dedicated to the
public domain under CC0 1.0 (<https://creativecommons.org/publicdomain/zero/1.0/>). They are not licensed under this
repository's AGPL-3.0.
