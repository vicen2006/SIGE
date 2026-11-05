package cl.sige.plataforma.gateway.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        "/actuator/**",
        "/api/identidad/auth/**"
    };

    private static final String DOCENTE = "DOCENTE";
    private static final String DIRECTIVO = "DIRECTIVO";
    private static final String INSPECTOR = "INSPECTOR";
    private static final String ADMINISTRATIVO = "ADMINISTRATIVO";
    private static final String APODERADO = "APODERADO";
    private static final String ESTUDIANTE = "ESTUDIANTE";

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers(PUBLIC_PATHS).permitAll()

                        // ---------- identidad ----------
                        .pathMatchers(HttpMethod.POST, "/api/identidad/personas",
                                "/api/identidad/personas/*/roles")
                            .hasAnyRole(ADMINISTRATIVO, DIRECTIVO)
                        .pathMatchers(HttpMethod.PATCH, "/api/identidad/personas/roles/*/desactivar")
                            .hasAnyRole(ADMINISTRATIVO, DIRECTIVO)

                        // ---------- estudiantes ----------
                        .pathMatchers(HttpMethod.POST, "/api/estudiantes/**")
                            .hasAnyRole(ADMINISTRATIVO, DIRECTIVO)

                        // ---------- academico ----------
                        .pathMatchers(HttpMethod.PATCH,
                                "/api/academico/periodos-academicos/*/subperiodos/*/cerrar")
                            .hasRole(DIRECTIVO)
                        .pathMatchers(HttpMethod.POST, "/api/academico/matriculas")
                            .hasRole(ADMINISTRATIVO)
                        .pathMatchers(HttpMethod.PATCH, "/api/academico/matriculas/**")
                            .hasRole(ADMINISTRATIVO)
                        .pathMatchers(HttpMethod.POST, "/api/academico/**")
                            .hasAnyRole(ADMINISTRATIVO, DIRECTIVO)
                        .pathMatchers(HttpMethod.PATCH, "/api/academico/**")
                            .hasAnyRole(ADMINISTRATIVO, DIRECTIVO)

                        // ---------- notas ----------
                        .pathMatchers(HttpMethod.POST, "/api/notas/solicitudes-exception-calificacion")
                            .hasRole(DOCENTE)
                        .pathMatchers(HttpMethod.PATCH,
                                "/api/notas/solicitudes-exception-calificacion/*/aprobar",
                                "/api/notas/solicitudes-exception-calificacion/*/rechazar")
                            .hasRole(DIRECTIVO)
                        .pathMatchers(HttpMethod.POST, "/api/notas/evaluaciones", "/api/notas/calificaciones")
                            .hasRole(DOCENTE)
                        .pathMatchers(HttpMethod.PATCH, "/api/notas/calificaciones/*")
                            .hasRole(DOCENTE)
                        .pathMatchers(HttpMethod.GET, "/api/notas/**")
                            .hasAnyRole(DOCENTE, DIRECTIVO, APODERADO, ESTUDIANTE)

                        // ---------- anotaciones ----------
                        .pathMatchers(HttpMethod.GET, "/api/anotaciones/anotaciones")
                            .hasAnyRole(INSPECTOR, DIRECTIVO)
                        .pathMatchers(HttpMethod.POST, "/api/anotaciones/**")
                            .hasAnyRole(DOCENTE, INSPECTOR)
                        .pathMatchers(HttpMethod.PATCH, "/api/anotaciones/**")
                            .hasAnyRole(DOCENTE, INSPECTOR)
                        .pathMatchers(HttpMethod.GET, "/api/anotaciones/**")
                            .hasAnyRole(DOCENTE, INSPECTOR, DIRECTIVO, APODERADO, ESTUDIANTE)

                        // ---------- mensajeria ----------
                        .pathMatchers(HttpMethod.POST, "/api/mensajeria/solicitudes-acceso-conversacion")
                            .hasAnyRole(INSPECTOR, DIRECTIVO)
                        .pathMatchers(HttpMethod.PATCH,
                                "/api/mensajeria/solicitudes-acceso-conversacion/*/aprobar",
                                "/api/mensajeria/solicitudes-acceso-conversacion/*/rechazar")
                            .hasRole(DIRECTIVO)
                        .pathMatchers(HttpMethod.GET,
                                "/api/mensajeria/solicitudes-acceso-conversacion/*/mensajes")
                            .hasAnyRole(INSPECTOR, DIRECTIVO)
                        .pathMatchers(HttpMethod.POST, "/api/mensajeria/conversaciones/**")
                            .hasAnyRole(DOCENTE, APODERADO)
                        .pathMatchers(HttpMethod.PATCH, "/api/mensajeria/conversaciones/**")
                            .hasAnyRole(DOCENTE, APODERADO)
                        .pathMatchers(HttpMethod.GET, "/api/mensajeria/conversaciones/**")
                            .hasAnyRole(DOCENTE, APODERADO, ESTUDIANTE)

                        // ---------- comunicaciones ----------
                        .pathMatchers(HttpMethod.POST, "/api/comunicaciones/comunicados")
                            .hasAnyRole(DIRECTIVO, DOCENTE)
                        .pathMatchers(HttpMethod.POST, "/api/comunicaciones/notificaciones")
                            .denyAll()

                        // ---------- calendario ----------
                        .pathMatchers(HttpMethod.POST, "/api/calendario/eventos-institucionales")
                            .hasAnyRole(DIRECTIVO, ADMINISTRATIVO)
                        .pathMatchers(HttpMethod.POST, "/api/calendario/reuniones")
                            .hasAnyRole(DOCENTE, INSPECTOR, DIRECTIVO)
                        .pathMatchers(HttpMethod.PATCH, "/api/calendario/reuniones/**")
                            .hasAnyRole(DOCENTE, INSPECTOR, DIRECTIVO)

                        // ---------- auditoria ----------
                        .pathMatchers(HttpMethod.POST, "/api/auditoria/**").denyAll()
                        .pathMatchers(HttpMethod.GET, "/api/auditoria/**").hasRole(DIRECTIVO)

                        // Todo lo demas (lecturas generales): cualquier usuario autenticado
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * El JWT trae el claim "roles" como lista de objetos {personaRolId, rol}.
     * Cada "rol" se convierte en la autoridad ROLE_<rol> (hasRole("DIRECTIVO") busca ROLE_DIRECTIVO).
     */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extraerAutoridades);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    private Collection<GrantedAuthority> extraerAutoridades(Jwt jwt) {
        List<Map<String, Object>> roles = jwt.getClaim("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(r -> (String) r.get("rol"))
                .filter(r -> r != null)
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}