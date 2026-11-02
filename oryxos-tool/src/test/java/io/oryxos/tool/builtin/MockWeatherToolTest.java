package io.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 演示用虚拟天气工具：不联网、每次返回一份自洽且可解析的天气数据。 */
class MockWeatherToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockWeatherTool tool = new MockWeatherTool();

    private JsonNode call(Map<String, Object> input) throws Exception {
        ToolResult result = tool.execute(input);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isNotBlank();
        return objectMapper.readTree(result.getContent());
    }

    @Test
    void 工具三件套齐全且名字固定() {
        assertThat(tool.getName()).isEqualTo("get_weather");
        assertThat(tool.getDescription()).isNotBlank().contains("city");
        assertThat(tool.getInputSchema()).contains("\"city\"");
    }

    @Test
    void 不传城市时默认上海() throws Exception {
        JsonNode weather = call(Map.of());

        assertThat(weather.get("city").asText()).isEqualTo("上海");
        assertThat(weather.get("source").asText()).isEqualTo("mock");
        // observedAt 必须是可解析的瞬间，前端才不需要猜格式
        assertThat(Instant.parse(weather.get("observedAt").asText())).isNotNull();
    }

    @Test
    void 传了城市就原样透传() throws Exception {
        assertThat(call(Map.of("city", "北京")).get("city").asText()).isEqualTo("北京");
        assertThat(call(Map.of("city", " 杭州 ")).get("city").asText()).isEqualTo("杭州");
    }

    @Test
    void 数值落在合理区间且确实随机() throws Exception {
        Set<Integer> temperatures = new HashSet<>();

        for (int i = 0; i < 40; i++) {
            JsonNode weather = call(Map.of());
            assertThat(weather.get("condition").asText()).isNotBlank();
            assertThat(weather.get("temperatureC").asInt()).isBetween(8, 32);
            assertThat(weather.get("humidity").asInt()).isBetween(25, 98);
            assertThat(weather.get("windKph").asInt()).isBetween(0, 30);
            temperatures.add(weather.get("temperatureC").asInt());
        }

        // 40 次采样全部同一个温度的概率可以忽略；这条断言防的是"随机写成了常量"
        assertThat(temperatures).hasSizeGreaterThan(1);
    }
}
