package io.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MockWeatherTool —— <b>虚拟</b>天气工具，工具名 {@code get_weather}。
 *
 * <p><b>这是演示/联调用的模拟实现，不是生产工具。</b>它不发起任何网络请求，
 * 每次调用随机生成一份自洽的天气数据返回。存在的意义只有一个：
 * 在没有真实天气 API 的情况下，让 ReAct 循环里"取数据 → 用数据"这条链路可以被完整验证一遍。</p>
 *
 * <p>刻意不经过 {@link io.oryxos.tool.sandbox.Sandbox}：本工具不触达文件、命令与外网，
 * 没有需要校验的出站动作。沙箱只约束真正有副作用的动作，给纯计算工具套一层校验没有意义。</p>
 *
 * <p><b>停用方式</b>：删掉 {@code ToolAutoConfiguration#weatherToolRegistration} 这个 Bean 即可，
 * 不影响其它任何工具。</p>
 */
public class MockWeatherTool implements OryxTool {

    /** 工具名（全局唯一，供 LLM Function Calling 引用）。 */
    public static final String NAME = "get_weather";

    private static final String PARAM_CITY = "city";

    /** 未指定城市时的默认城市。 */
    private static final String DEFAULT_CITY = "上海";

    /** 天气状况。湿度区间与之绑定，避免出现"晴 + 95% 湿度"这种一眼假的数据。 */
    private record Condition(String name, int humidityLow, int humidityHigh) {
    }

    private static final Condition[] CONDITIONS = {
            new Condition("晴", 25, 55),
            new Condition("多云", 35, 65),
            new Condition("阴", 45, 75),
            new Condition("小雨", 70, 95),
            new Condition("雷阵雨", 75, 98),
            new Condition("雾", 80, 98)
    };

    private static final int TEMPERATURE_LOW = 8;
    private static final int TEMPERATURE_HIGH = 32;
    private static final int WIND_HIGH_KPH = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "查询指定城市的当前天气。这是演示用的模拟实现：不联网，每次随机返回一份天气数据。"
                + "参数：city（可选，城市名，缺省上海）。"
                + "返回 JSON：city / condition / temperatureC / humidity / windKph / observedAt / source。";
    }

    @Override
    public String getInputSchema() {
        return """
                {"type":"object","properties":{"city":{"type":"string","description":"城市名，可省略，缺省为上海"}}}
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String city = asString(input == null ? null : input.get(PARAM_CITY));
        if (city == null || city.isBlank()) {
            city = DEFAULT_CITY;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Condition condition = CONDITIONS[random.nextInt(CONDITIONS.length)];
        int temperature = random.nextInt(TEMPERATURE_LOW, TEMPERATURE_HIGH + 1);
        int humidity = random.nextInt(condition.humidityLow(), condition.humidityHigh() + 1);
        int wind = random.nextInt(0, WIND_HIGH_KPH + 1);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("city", city.trim());
        payload.put("condition", condition.name());
        payload.put("temperatureC", temperature);
        payload.put("humidity", humidity);
        payload.put("windKph", wind);
        payload.put("observedAt", Instant.now().toString());
        payload.put("source", "mock");

        try {
            return ToolResult.ok(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            // 序列化失败属于实现缺陷，但绝不能把异常抛给循环：给出可读的降级文本
            return ToolResult.ok(city + " " + condition.name() + " " + temperature + "℃ 湿度 "
                    + humidity + "% 风速 " + wind + "km/h");
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
