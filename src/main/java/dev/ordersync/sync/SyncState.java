package dev.ordersync.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** The watermark: everything modified before it has been handled (synced or dead-lettered). */
@Entity
@Table(name = "sync_state")
public class SyncState {

    @Id
    @Column(name = "stream_name")
    private String streamName;

    @Column(name = "watermark", nullable = false)
    private Instant watermark;

    protected SyncState() {
    }

    public SyncState(String streamName, Instant watermark) {
        this.streamName = streamName;
        this.watermark = watermark;
    }

    public String streamName() {
        return streamName;
    }

    public Instant watermark() {
        return watermark;
    }

    public void advanceTo(Instant newWatermark) {
        if (newWatermark.isAfter(watermark)) {
            this.watermark = newWatermark;
        }
    }
}
