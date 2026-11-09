package kr.ganzithon.backend.chat;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryArchiverTest {
    @Test
    void fullQueueIsStillBoundedToTenTransactionsPerTick() {
        ChatMemoryService memory = mock(ChatMemoryService.class);
        when(memory.archiveOld(100)).thenReturn(100);
        new ChatMemoryArchiver(memory).archiveDue();
        verify(memory, times(10)).archiveOld(100);
    }

    @Test
    void shortBatchAndFailureBothStopTheCurrentTick() {
        ChatMemoryService shortQueue = mock(ChatMemoryService.class);
        when(shortQueue.archiveOld(100)).thenReturn(100, 9);
        new ChatMemoryArchiver(shortQueue).archiveDue();
        verify(shortQueue, times(2)).archiveOld(100);

        ChatMemoryService broken = mock(ChatMemoryService.class);
        when(broken.archiveOld(100)).thenThrow(new IllegalStateException("sensitive database error"));
        new ChatMemoryArchiver(broken).archiveDue();
        verify(broken, times(1)).archiveOld(100);
    }
}
