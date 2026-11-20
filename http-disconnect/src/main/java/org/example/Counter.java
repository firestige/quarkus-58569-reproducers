package org.example;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Minimal entity used only by the cancellation reproducer. */
@Entity
@Table(name = "repro_counter")
public class Counter {
  @Id public long id;
  public long value;
}
