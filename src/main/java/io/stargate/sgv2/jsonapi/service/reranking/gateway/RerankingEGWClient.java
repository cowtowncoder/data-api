package io.stargate.sgv2.jsonapi.service.reranking.gateway;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.smallrye.mutiny.Uni;
import io.stargate.embedding.gateway.EmbeddingGateway;
import io.stargate.embedding.gateway.RerankingService;
import io.stargate.sgv2.jsonapi.api.request.RerankingCredentials;
import io.stargate.sgv2.jsonapi.exception.ErrorCodeV1;
import io.stargate.sgv2.jsonapi.exception.JsonApiException;
import io.stargate.sgv2.jsonapi.service.reranking.configuration.RerankingProvidersConfig;
import io.stargate.sgv2.jsonapi.service.reranking.operation.RerankingProvider;
import java.util.*;
import java.util.stream.Collectors;

/** Grpc client to make reranking Grpc requests to reranking API inside EmbeddingGatewayService */
public class RerankingEGWClient extends RerankingProvider {

  private static final String DEFAULT_TENANT_ID = "default";

  /** Map to the value of `Token` in the header */
  private static final String DATA_API_TOKEN = "DATA_API_TOKEN";

  /**
   * key name of the reranking service provider. Note, the request self-hosted Nvidia reranker
   * service does not need to specify an auth token in Data API request header. Instead, we use the
   * Astra token to authenticate the request.
   */
  private static final String RERANKING_API_KEY = "RERANKING_API_KEY";

  private String provider;

  private Optional<String> tenant;
  private Optional<String> authToken;
  private String modelName;
  private RerankingService rerankingGrpcService;
  Map<String, String> authentication;
  private String commandName;

  public RerankingEGWClient(
      String baseUrl,
      RerankingProvidersConfig.RerankingProviderConfig.ModelConfig.RequestProperties
          requestProperties,
      String provider,
      Optional<String> tenant,
      Optional<String> authToken,
      String modelName,
      RerankingService rerankingGrpcService,
      Map<String, String> authentication,
      String commandName) {
    super(baseUrl, modelName, requestProperties);
    this.provider = provider;
    this.tenant = tenant;
    this.authToken = authToken;
    this.modelName = modelName;
    this.rerankingGrpcService = rerankingGrpcService;
    this.authentication = authentication;
    this.commandName = commandName;
  }

  @Override
  public Uni<RerankingBatchResponse> rerank(
      int batchId, String query, List<String> passages, RerankingCredentials rerankingCredentials) {

    // Build the reranking provider request in grpc request
    final EmbeddingGateway.ProviderRerankingRequest.RerankingRequest rerankingRequest =
        EmbeddingGateway.ProviderRerankingRequest.RerankingRequest.newBuilder()
            .setModelName(modelName)
            .setQuery(query)
            .addAllPassages(passages)
            .setCommandName(commandName)
            .build();

    // Build the reranking provider context in grpc request
    var contextBuilder =
        EmbeddingGateway.ProviderRerankingRequest.ProviderContext.newBuilder()
            .setProviderName(provider)
            .setTenantId(tenant.orElse(DEFAULT_TENANT_ID))
            .putAuthTokens(DATA_API_TOKEN, rerankingCredentials.token());
    if (rerankingCredentials.apiKey().isPresent()) {
      contextBuilder.putAuthTokens(RERANKING_API_KEY, rerankingCredentials.apiKey().get());
    }
    final EmbeddingGateway.ProviderRerankingRequest.ProviderContext providerContext =
        contextBuilder.build();

    // Built the Grpc request
    final EmbeddingGateway.ProviderRerankingRequest grpcRerankingRequest =
        EmbeddingGateway.ProviderRerankingRequest.newBuilder()
            .setRerankingRequest(rerankingRequest)
            .setProviderContext(providerContext)
            .build();

    Uni<EmbeddingGateway.RerankingResponse> grpcRerankingResponse;
    try {
      grpcRerankingResponse = rerankingGrpcService.rerank(grpcRerankingRequest);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.DEADLINE_EXCEEDED)) {
        throw ErrorCodeV1.RERANKING_PROVIDER_TIMEOUT.toApiException(e, e.getMessage());
      }
      throw e;
    }

    return grpcRerankingResponse
        .onItem()
        .transform(
            resp -> {
              if (resp.hasError()) {
                throw new JsonApiException(
                    ErrorCodeV1.valueOf(resp.getError().getErrorCode()),
                    resp.getError().getErrorMessage());
              }
              return RerankingBatchResponse.of(
                  batchId,
                  resp.getRanksList().stream()
                      .map(rank -> new Rank(rank.getIndex(), rank.getScore()))
                      .collect(Collectors.toList()),
                  new Usage(resp.getUsage().getPromptTokens(), resp.getUsage().getTotalTokens()));
            });
  }
}
