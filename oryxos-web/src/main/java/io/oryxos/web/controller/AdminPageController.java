package io.oryxos.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * AdminPageController —— 把 {@code /admin} 指到静态首页。
 *
 * <p>静态资源处理器只认具体文件（{@code /admin/index.html}）或目录带斜杠（{@code /admin/}），
 * 访问 {@code /admin} 本身不会自动落盘。一行重定向比调 {@code WebMvcConfigurer} 更省，
 * 也让"打开 /admin 就能用"这件事可被测试断言。</p>
 *
 * <p>首页由 {@code admin-web/}（Vite + React）构建产出，落在 {@code static/admin/}；
 * 页面本身没有独立后端：所有数据来自 {@code /api/v1/*}。</p>
 */
@Controller
public class AdminPageController {

    /**
     * 两个入口都收：{@code /admin}（手输）与 {@code /admin/}（带斜杠的目录形式）。
     *
     * <p>后者单独映射是因为静态资源处理器只认具体文件：{@code /admin/} 不会自动落到
     * {@code static/admin/index.html}，而是 404。少这一行，用户复制粘贴带斜杠的地址就会看到一片空白。</p>
     */
    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        return "redirect:/admin/index.html";
    }
}
