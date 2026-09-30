package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.tools.ToolCallRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One lookup the model made for an answer; see V22__ai_tool_calls.sql. */
@Entity
@Table(name = "ai_tool_calls")
@Getter
@NoArgsConstructor
public class AiToolCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(nullable = false, length = 50)
    private String tool;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String arguments;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(nullable = false)
    private boolean ok;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public AiToolCall(Long messageId, ToolCallRecord record) {
        this.messageId = messageId;
        this.tool = record.tool();
        this.arguments = record.arguments();
        this.result = record.result();
        this.ok = record.ok();
        this.durationMs = (int) Math.min(Integer.MAX_VALUE, record.durationMs());
    }

    public ToolCallRecord toRecord() {
        return new ToolCallRecord(tool, arguments, result, ok, durationMs);
    }
}
