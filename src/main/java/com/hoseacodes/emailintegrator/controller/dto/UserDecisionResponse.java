package com.hoseacodes.emailintegrator.controller.dto;

/**
 * Response for an approve or deny decision.
 *
 * <p>Replaces the nested {@code Map.of(...)} literals the controller previously built, which
 * produced a shape no client could rely on and that OpenAPI documented as an untyped object.
 *
 * <p>Note what is <em>not</em> here: the old response carried an {@code emailSent} boolean, which
 * was the return value of a send that swallowed its own failures. A caller could not tell a
 * genuine delivery failure from a disabled mail service. Notification failures now surface as an
 * error response, so a 200 means the decision was recorded <em>and</em> the user was told.
 *
 * @param status     the decision applied, {@code APPROVED} or {@code DENIED}
 * @param email      the user the decision applies to
 * @param name       display name used in the notification
 * @param messageId  provider message id for the notification email, for later correlation
 */
public record UserDecisionResponse(String status, String email, String name, String messageId) {
}
