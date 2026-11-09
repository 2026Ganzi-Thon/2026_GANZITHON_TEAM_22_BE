package kr.ganzithon.backend.chat;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatUsageController {
    private final ChatBudgetService budget;

    public ChatUsageController(ChatBudgetService budget) { this.budget = budget; }

    @GetMapping("/api/v1/chat/usage")
    public ChatBudgetService.UsageView usage(@RequestAttribute("userId") UUID userId) {
        return budget.usage(userId);
    }
}
