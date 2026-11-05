# SIGE Escolar — Sistema Integral de Gestión Estudiantil

Plataforma de gestión escolar construida con arquitectura de microservicios, diseñada como proyecto de portafolio y aprendizaje para demostrar un flujo completo de diseño de dominio, arquitectura distribuida, comunicación síncrona y asíncrona, y despliegue containerizado.

## Stack tecnológico

- **Java 21** + **Spring Boot 4.1**
- **Spring Cloud** (Config Server, Eureka, Gateway, OpenFeign)
- **MySQL 8.4** — una base de datos lógica por microservicio
- **Apache Kafka** — comunicación asíncrona basada en eventos
- **WebSocket (STOMP)** — mensajería en tiempo real
- **Docker Compose** — orquestación completa del ecosistema
- **Flyway** — versionado de esquema de base de datos
- **JWT** — autenticación entre Gateway y microservicios

## Arquitectura

El sistema está compuesto por 9 microservicios de dominio, más 3 componentes de infraestructura:

| Componente | Puerto | Responsabilidad |
|---|---|---|
| Config Server | 8888 | Configuración centralizada (Git como backend) |
| Eureka Server | 8761 | Registro y descubrimiento de servicios |
| Gateway | 8080 | Punto de entrada único, enrutamiento, validación JWT |
| ms-identidad-acceso | 8081 | Personas, roles, autenticación |
| ms-estudiantes | 8082 | Estudiantes, apoderados y sus relaciones |
| ms-academico | 8083 | Niveles, periodos, subperiodos, cursos, matrículas, asignaciones docentes |
| ms-evaluaciones-notas | 8084 | Evaluaciones, calificaciones, solicitudes de excepción de nota |
| ms-anotaciones | 8085 | Anotaciones disciplinarias y académicas, bandeja de inspectoría |
| ms-mensajeria | 8086 | Conversaciones, solicitudes de acceso y mensajería en tiempo real vía WebSocket |
| ms-comunicaciones | 8087 | Comunicados institucionales y notificaciones |
| ms-calendario-reuniones | 8088 | Reuniones, eventos institucionales y calendario consolidado |
| ms-auditoria | 8089 | Registro de auditoría, alimentado por eventos Kafka |

Cada microservicio tiene su propia base de datos lógica (esquema aislado en un servidor MySQL compartido para desarrollo local), y ningún servicio accede directamente a los datos de otro — toda comunicación cruzada ocurre vía **OpenFeign** (síncrona) o **Kafka** (asíncrona).

### Documentos de diseño

El proceso de diseño completo está documentado en `docs/`:
- `SIGE-dominio-consolidado.md` — análisis del dominio de negocio
- `MODELO_LOGICO_BD.md` — modelo relacional y decisiones de transformación
- `ARQUITECTURA_MICROSERVICIOS.md` — arquitectura, eventos y decisiones técnicas

## Comunicación entre servicios

**Síncrona (OpenFeign):** validaciones cruzadas en tiempo de escritura — por ejemplo, `ms-academico` valida contra `ms-identidad-acceso` que un `personaRolId` corresponda a un Docente activo antes de crear una Asignación Docente, o que sea un Directivo activo antes de cerrar un Subperiodo.

**Asíncrona (Kafka):** los microservicios de dominio publican eventos de negocio a sus propios tópicos (`academico.eventos`, `evaluaciones.eventos`, `anotaciones.eventos`, etc.), y todos publican además al tópico común `auditoria.eventos`, consumido por `ms-auditoria` para generar el registro de auditoría de forma automática y desacoplada. Consumidores de negocio:

- `ms-comunicaciones` consume `AnotacionCreada` para generar notificaciones.
- `ms-mensajeria` consume su propio `MensajeEnviado` y lo reenvía por WebSocket.
- `ms-calendario-reuniones` consume `EvaluacionCreada` y los cierres de Subperiodo/Periodo para mantener una **proyección local de solo lectura** (patrón CQRS puntual), de modo que el calendario no dependa de llamadas síncronas a otros servicios en cada consulta.

**Tiempo real (WebSocket):** `ms-mensajeria` reenvía vía STOMP a los clientes suscritos a `/topic/conversaciones/{id}`, permitiendo mensajería en vivo sin polling.

## Reglas de negocio destacadas

Decisiones de dominio implementadas y verificadas de punta a punta:

- **Solicitud de excepción de calificación:** el Docente propone el nuevo resultado al crear la solicitud; el Directivo solo la autoriza o rechaza. Solo puede existir una solicitud PENDIENTE por calificación, y aprobar/rechazar únicamente aplica a solicitudes PENDIENTE.
- **Anotaciones:** anular exige motivo y actor, y queda auditado. Existe una bandeja transversal (`GET /anotaciones`, paginada y con filtros) para Inspectoría.
- **Mensajería:** el acceso a una conversación pasa por una solicitud que se aprueba o rechaza (el rechazo exige motivo). Cada persona puede listar sus conversaciones (`GET /conversaciones/persona/{personaId}`). En esta versión el estudiante no escribe mensajes.
- **Matrícula:** `RETIRADA` y `FINALIZADA` son estados terminales independientes; las únicas transiciones son ACTIVA→FINALIZADA (cambio de curso) y ACTIVA→RETIRADA (exige motivo). Reincorporar a un estudiante crea una matrícula nueva.
- **Cierre académico:** lo ejecuta un Directivo a nivel de Subperiodo; el Periodo se cierra automáticamente cuando todos sus subperiodos están cerrados. El cierre es terminal (no hay reapertura).
- **Calendario consolidado:** `GET /calendario?desde=AAAA-MM-DD&hasta=AAAA-MM-DD` (máximo 366 días) une en una sola lista, ordenada por fecha, reuniones, eventos institucionales, evaluaciones y cierres de subperiodo y periodo.

## Cómo levantar el proyecto

Requiere Docker y Docker Compose.

> **Importante — configuración centralizada:** el repositorio de configuración (`sige-config-repo`) es local y **no forma parte de este repositorio** (se sirve al Config Server vía `file://`). Hoy `docker-compose.yml` lo monta desde una ruta fija (`C:/Users/vicen/sige-config-repo`, servicio `config-server`). Para reproducir el entorno en otra máquina hay que crear ese repositorio Git local (rama `main`) con el `.yml` de cada servicio y ajustar la ruta del volumen en el compose. Dejar esto reproducible con plantillas está en el roadmap.

```bash
git clone https://github.com/vicen2006/SIGE.git
cd SIGE
docker compose up -d
```

Esto levanta los 14 contenedores (MySQL, Kafka, Config Server, Eureka, Gateway y los 9 microservicios). En equipos con RAM limitada (recomendado 8GB+), es posible levantar solo un subconjunto según lo que se quiera probar:

```bash
docker compose up -d mysql kafka config-server eureka-server ms-identidad-acceso ms-estudiantes ms-academico
```

Algunos flujos dependen de otros servicios por validaciones Feign (por ejemplo, crear una matrícula requiere `ms-estudiantes` e `ms-identidad-acceso`), y las consultas de auditoría requieren `ms-auditoria` corriendo **antes** de ejecutar la acción.

Para confirmar el estado de los contenedores:
```bash
docker compose ps
```

Los que tienen healthcheck configurado deberían mostrar `healthy` una vez que terminan de inicializar. El dashboard de Eureka (`http://localhost:8761`) muestra además todos los servicios registrados.

Para detener todo sin borrar datos: `docker compose stop`. Evitar `docker compose down -v`, que elimina los volúmenes (y los datos de prueba).

## Desarrollo local (sin Docker)

Cada microservicio puede ejecutarse individualmente con Maven:

```bash
cd ms-identidad-acceso
./mvnw spring-boot:run
```

Requiere Config Server y Eureka corriendo previamente, y una base de datos MySQL local con el esquema correspondiente (ver migraciones Flyway en `src/main/resources/db/migration` de cada servicio). Las migraciones aplicadas nunca se editan: todo cambio de esquema es una migración nueva (`V{n}__*.sql`).

## Estado del proyecto

- 9 microservicios de dominio con validación cruzada vía Feign
- Infraestructura de microservicios (Config Server, Eureka, Gateway)
- Autenticación JWT
- Docker Compose completo
- Arquitectura orientada a eventos con Kafka, incluyendo una proyección local para el calendario
- Mensajería en tiempo real vía WebSocket
- Auditoría automática de operaciones relevantes

### Roadmap / limitaciones conocidas

- **Autorización por rol:** hoy cualquier JWT válido puede llamar a cualquier endpoint, y varios endpoints reciben el actor (`personaRolId`) en el cuerpo de la petición en vez de obtenerlo del token. Es el siguiente paso (Spring Security), e incluye restringir endpoints sensibles como la bandeja de anotaciones y el listado de conversaciones por persona. Hasta entonces, algunos eventos de auditoría de Matrícula y Periodo se registran sin actor.
- **Pruebas automatizadas:** no implementadas aún (JUnit 5 + Mockito planificados). Los flujos se verificaron de punta a punta con REST Client.
- **Frontend:** en diseño; no implementado.
- **Estudiante en mensajería:** en esta versión no escribe mensajes; planificado para la v2.0.
- **Calendario:** los cierres de Subperiodo/Periodo aparecen solo después de cerrarse (la proyección se alimenta del evento de cierre), no se refleja el cambio de fecha de una evaluación, y la vista es global (sin filtro por persona o curso).
- **Notificaciones automáticas:** `ms-comunicaciones` hoy solo consume `AnotacionCreada`; el resto de los eventos catalogados (calificaciones, matrículas, reuniones) siguen el mismo patrón y quedan como extensión natural.
- **Entorno reproducible:** falta incluir plantillas del `sige-config-repo` y parametrizar su ruta en `docker-compose.yml`.
- **Transactional Outbox:** la publicación de eventos Kafka ocurre dentro de la misma transacción de negocio sin patrón Outbox formal; existe una ventana teórica de inconsistencia si Kafka no está disponible en el momento de publicar (limitación conocida, no bloqueante para el alcance de este proyecto).