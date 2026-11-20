package xyz.firestige.investmentadviser.infrastructure.persistence.postgres.investment.ledger.pojo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Small durable command receipt and canonical content hash used for original-key recovery. Optional
 * source triplets bind only original records; alias receipts retain the original business identity
 * without duplicating financial entries. Receipt JSON is a bounded object, not ledger state.
 */
@Entity
@Table(name = "ledger_commands")
@Check(
    constraints =
        "((source_system IS NULL AND source_namespace IS NULL AND source_record_id IS NULL) OR (source_system IS NOT NULL AND source_namespace IS NOT NULL AND source_record_id IS NOT NULL)) AND jsonb_typeof(receipt)='object' AND octet_length(receipt::text)<8192")
public class LedgerCommandPO {
  @Id
  @Column(name = "command_id", nullable = false)
  public UUID commandId;

  @Column(name = "generation", nullable = false)
  public UUID generation;

  @Column(name = "business_id", nullable = false)
  public UUID businessId;

  @Column(name = "canonical_hash", columnDefinition = "char(64)", nullable = false)
  public String canonicalHash;

  @Column(name = "source_system", columnDefinition = "text")
  public String sourceSystem;

  @Column(name = "source_namespace", columnDefinition = "text")
  public String sourceNamespace;

  @Column(name = "source_record_id", columnDefinition = "text")
  public String sourceRecordId;

  @Column(name = "source_hash", columnDefinition = "char(64)")
  public String sourceHash;

  @Column(name = "is_alias", nullable = false)
  public boolean alias;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "receipt", columnDefinition = "jsonb", nullable = false)
  public String receipt;
}
