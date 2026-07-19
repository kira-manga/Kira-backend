package me.manga.kira.backend.common.web

import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.nio.charset.StandardCharsets

/** Keeps every MVC JSON response on the documented explicit UTF-8 media type. */
@ControllerAdvice
class JsonUtf8ResponseAdvice : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        if (selectedContentType.subtype == "json" || selectedContentType.subtype.endsWith("+json")) {
            response.headers.contentType =
                MediaType(selectedContentType.type, selectedContentType.subtype, StandardCharsets.UTF_8)
        }
        return body
    }
}
