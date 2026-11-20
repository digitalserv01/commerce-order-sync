-- One dead letter per order version: a parked order must not be parked again on every poll.
DROP INDEX idx_dead_letters_order;
CREATE UNIQUE INDEX uq_dead_letters_order_version ON dead_letters (order_code, order_version);
