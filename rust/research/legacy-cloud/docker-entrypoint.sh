#!/bin/bash
set -e
cd /app

# 下载 gamedata（首次启动）
if [ ! -f gamedata/constants.json ]; then
    echo "下载 gamedata..."
    mkdir -p gamedata
    BASE=https://raw.githubusercontent.com/xulai1001/umaai-rs/master/gamedata
    for f in constants.json cardDB.json umaDB.json text_data_dict.json events.json scenario_ramen.json scenario_onsen.json default_config.toml; do
        curl -sSL "$BASE/$f" -o "gamedata/$f" || echo "WARN: $f"
    done
fi

# 下载优化策略（如果有）
if [ ! -f strategy_optimized.json ]; then
    echo "尝试下载优化策略..."
    curl -sSL "https://raw.githubusercontent.com/xf8410/uma-juece-ramen/workbench/upstream-search-integration/rust/strategy_optimized.json" \
        -o strategy_optimized.json 2>/dev/null || echo "无优化策略，用默认"
fi

export STRATEGY_IN=/app/strategy_optimized.json
export UMAI_DATA_DIR=/app/gamedata
echo "启动搜索服务..."
exec ./ramen_server
