package com.persimmon.ddd.openapi;

import com.persimmon.ddd.core.ErrorCategory;
import com.persimmon.ddd.web.ProblemDescriptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

/**
 * springdoc sees controllers and read models by reflection; it cannot see the problem details the
 * web module's advice produces. This adds the {@code ProblemDetail} and {@code FieldError} schemas
 * and attaches the default problem responses to every operation that does not declare its own
 * response for that status.
 */
public class ProblemResponsesCustomizer implements GlobalOpenApiCustomizer {
  static final String PROBLEM = "ProblemDetail";
  static final String FIELD_ERROR = "FieldError";
  private static final List<ErrorCategory> DEFAULTS =
      List.of(
          ErrorCategory.VALIDATION,
          ErrorCategory.NOT_FOUND,
          ErrorCategory.CONFLICT,
          ErrorCategory.DOMAIN_RULE,
          ErrorCategory.UNEXPECTED);

  @Override
  public void customise(OpenAPI openApi) {
    if (openApi.getComponents() == null) {
      openApi.setComponents(new Components());
    }
    openApi.getComponents().addSchemas(FIELD_ERROR, fieldError()).addSchemas(PROBLEM, problemDetail());
    if (openApi.getPaths() != null) {
      openApi.getPaths().values().stream().map(PathItem::readOperations).flatMap(List::stream).forEach(this::attach);
    }
  }

  private void attach(Operation operation) {
    if (operation.getResponses() == null) {
      operation.setResponses(new ApiResponses());
    }
    for (ErrorCategory category : DEFAULTS) {
      ProblemDescriptor descriptor = ProblemDescriptor.of(category);
      String status = String.valueOf(descriptor.status());
      if (!operation.getResponses().containsKey(status)) {
        operation
            .getResponses()
            .addApiResponse(
                status,
                new ApiResponse()
                    .description(descriptor.title() + " (problem type " + descriptor.type() + ")")
                    .content(
                        new Content()
                            .addMediaType(
                                "application/problem+json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)))));
      }
    }
  }

  private static Schema<?> problemDetail() {
    return new ObjectSchema()
        .description("RFC 9457 problem detail with this library's extensions")
        .addProperty("type", new StringSchema().description("problem type URI").example("/problems/domain-rule-violation"))
        .addProperty("title", new StringSchema())
        .addProperty("status", new IntegerSchema().example(422))
        .addProperty("detail", new StringSchema())
        .addProperty("instance", new StringSchema())
        .addProperty("code", new StringSchema().description("machine-readable error code").example("ordering.credit-exceeded"))
        .addProperty("requestId", new StringSchema())
        .addProperty("traceId", new StringSchema().description("present when tracing is on"))
        .addProperty("errors", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/" + FIELD_ERROR)));
  }

  private static Schema<?> fieldError() {
    return new ObjectSchema()
        .addProperty("field", new StringSchema().example("lines[0].quantity"))
        .addProperty("code", new StringSchema().example("Positive"))
        .addProperty("message", new StringSchema());
  }
}
