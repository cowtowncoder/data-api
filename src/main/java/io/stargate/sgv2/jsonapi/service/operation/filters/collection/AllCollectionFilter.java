package io.stargate.sgv2.jsonapi.service.operation.filters.collection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.stargate.sgv2.jsonapi.config.constants.DocumentConstants;
import io.stargate.sgv2.jsonapi.exception.ErrorCodeV1;
import io.stargate.sgv2.jsonapi.service.operation.builder.BuiltCondition;
import io.stargate.sgv2.jsonapi.service.operation.builder.BuiltConditionPredicate;
import io.stargate.sgv2.jsonapi.service.operation.builder.ConditionLHS;
import io.stargate.sgv2.jsonapi.service.operation.builder.JsonTerm;
import io.stargate.sgv2.jsonapi.service.shredding.collections.DocValueHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Filter for document where all values exists for an array */
public class AllCollectionFilter extends CollectionFilter {
  private final List<Object> arrayValue;
  private final boolean negation;

  public AllCollectionFilter(String path, List<Object> arrayValue, boolean negation) {
    super(path);
    this.arrayValue = arrayValue;
    this.negation = negation;
  }

  public boolean isNegation() {
    return negation;
  }

  /**
   * DO not update the new document from an upsert for this array operation
   *
   * @param nodeFactory
   * @return
   */
  @Override
  protected Optional<JsonNode> jsonNodeForNewDocument(JsonNodeFactory nodeFactory) {
    return Optional.empty();
  }

  //    @Override
  //    public JsonNode asJson(JsonNodeFactory nodeFactory) {
  //        return DBFilterBase.getJsonNode(nodeFactory, arrayValue);
  //    }
  //
  //    @Override
  //    public boolean canAddField() {
  //        return false;
  //    }

  /* allFilter will populate a list of BuiltCondition, which will be used to logical AND together later
   */
  public List<BuiltCondition> getAll() {
    final ArrayList<BuiltCondition> result = new ArrayList<>();
    for (Object value : arrayValue) {
      this.collectionIndexUsage.arrayContainsTag = true;
      result.add(
          BuiltCondition.of(
              ConditionLHS.column(DocumentConstants.Columns.DATA_CONTAINS_COLUMN_NAME),
              negation ? BuiltConditionPredicate.NOT_CONTAINS : BuiltConditionPredicate.CONTAINS,
              new JsonTerm(getHashValue(new DocValueHasher(), getPath(), value))));
    }
    return result;
  }

  @Override
  public BuiltCondition get() {
    throw ErrorCodeV1.SERVER_INTERNAL_ERROR.toApiException(
        "For $all filter we always use getAll() method");
  }
}
