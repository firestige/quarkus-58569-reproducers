package xyz.firestige.investmentadviser.infrastructure.persistence.postgres.investment.ledger.pojo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The singleton ledger boundary, with its business version and reset generation. Initialization
 * times may be absent before initialization. Confirmations are small JSON values; manual rates
 * remain separately bounded rows. Version advances per accepted command and is not an ORM
 * optimistic-lock version. This row never owns an entries collection.
 */
@Entity
@Table(
    name = "ledger_meta",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = "singleton"),
      @UniqueConstraint(columnNames = "generation")
    })
@Check(constraints = "singleton AND version>=0 AND policy_version>=0")
public class LedgerMetaPO {
  @Id
  @Column(name = "ledger_id", nullable = false)
  public UUID ledgerId;

  @Column(name = "singleton", nullable = false)
  public boolean singleton;

  @Column(name = "generation", nullable = false)
  public UUID generation;

  @Column(name = "version", nullable = false)
  public long version;

  @Column(name = "initialized", nullable = false)
  public boolean initialized;

  @Column(name = "initialized_at")
  public Instant initializedAt;

  @Column(name = "recorded_at")
  public Instant recordedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "confirmations", columnDefinition = "jsonb", nullable = false)
  public Map<String, String> confirmations;

  @Column(name = "report_currency", columnDefinition = "char(3)", nullable = false)
  public String reportCurrency;

  @Column(name = "policy_version", nullable = false)
  public long policyVersion;
}
