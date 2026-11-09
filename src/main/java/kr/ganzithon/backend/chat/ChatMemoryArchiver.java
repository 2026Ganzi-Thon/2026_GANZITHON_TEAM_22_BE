package kr.ganzithon.backend.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded archiver: each batch uses ChatMemoryService's own transaction. */
@Component
@ConditionalOnProperty(name = "ganzi.chat.memory.archive-enabled", havingValue = "true", matchIfMissing = true)
public class ChatMemoryArchiver {
    private static final Logger log = LoggerFactory.getLogger(ChatMemoryArchiver.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES_PER_TICK = 10;
    private static final long MAX_NANOS_PER_TICK = 30_000_000_000L;

    private final ChatMemoryService memory;

    public ChatMemoryArchiver(ChatMemoryService memory) {
        this.memory = memory;
    }

    @Scheduled(fixedDelayString = "${ganzi.chat.memory.archive-interval-ms:60000}",
            initialDelayString = "${ganzi.chat.memory.archive-initial-delay-ms:60000}")
    public void archiveDue() {
        long started = System.nanoTime();
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            if (System.nanoTime() - started >= MAX_NANOS_PER_TICK) break;
            try {
                if (memory.archiveOld(BATCH_SIZE) < BATCH_SIZE) break;
            } catch (RuntimeException exception) {
                // No exception message or payload is logged: database errors can contain user data.
                log.error("Chat archive batch stopped: {}", exception.getClass().getSimpleName());
                break;
            }
        }
    }
}
