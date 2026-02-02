# Microserviço Radares 

Este projeto é um excelente exemplo de uma arquitetura moderna de microsserviços voltada para o processamento de grandes volumes de dados (**Big Data**) e busca geolocalizada. Ele utiliza o ecossistema **Spring Boot 3.2.7** com **Java 21**, aproveitando recursos avançados de performance e organização.

## 🚀 Pilares Tecnológicos e Infraestrutura

* **Java 21 & Maven:** O projeto utiliza a versão LTS mais recente do Java, garantindo acesso a melhorias de performance e sintaxe. O **Maven** gerencia dependências complexas, como o **Spring Cloud** para microsserviços.
* **Spring Boot 3.2.7:** Atua como o framework base, facilitando a configuração de segurança, persistência e exposição de APIs REST.
* **PostgreSQL com PostGIS (Hibernate Spatial):** O banco de dados armazena coordenadas geográficas através da dependência `hibernate-spatial`. Isso permite que o Java processe tipos geométricos, possibilitando buscas por raio de distância com funções como `ST_DWithin`.
* **Docker & Docker Compose:** O ambiente é isolado em containers através do arquivo `docker-compose.yml`, garantindo que o serviço rode de forma idêntica em desenvolvimento e produção.

## 🏗️ Arquitetura e Mensageria

* **Spring Cloud Config & Eureka:** O projeto utiliza configurações centralizadas via `configserver` e registro de serviços com `eureka-client`, permitindo uma arquitetura distribuída e escalável.
* **RabbitMQ (Mensageria Assíncrona):** Ao salvar novos registros, o sistema publica mensagens na `radares_exchange` de forma assíncrona. Isso promove o desacoplamento, permitindo que outros sistemas consumam os dados sem travar a API principal.

## 📐 Padrões de Projeto (Design Patterns)

* **Pattern Repository:** Utiliza `JpaRepository` e `JpaSpecificationExecutor` para isolar a lógica de acesso aos dados. Foram implementadas **Native Queries** para otimização de performance em consultas complexas com `DISTINCT ON`.
* **Pattern DTO (Data Transfer Object):** Separação clara entre as entidades de banco de dados (`Radars`) e os objetos de transferência (`RadarsDTO`), garantindo segurança e flexibilidade na formatação dos dados.
* **Pattern Specification:** O uso de `RadarsSpecification` permite a criação de filtros dinâmicos e reutilizáveis para consultas com múltiplos parâmetros opcionais.

## ⏱️ Processamento em Lote e Schedulers

* **Job de Vinculação de Localização:** A classe `LocalizacaoScheduler` executa tarefas automáticas a cada 5 minutos para processar registros pendentes de coordenadas.
* **Otimização de Performance:** Implementação de **Batch Processing** (processamento em lotes) com limites de registros por vez, evitando sobrecarga no banco de dados.

## 🛠️ Boas Práticas e Performance

* **Caching:** Uso de `@EnableCaching` e `@Cacheable` no `RadarsService` para reduzir o acesso ao disco em consultas frequentes, como localizações para o mapa.
* **Execução Assíncrona:** Utilização de `ExecutorService` (Thread Pool) para disparar mensagens ao RabbitMQ em background, minimizando o tempo de resposta da API.
* **Logs e Observabilidade:** Implementação de `logstash-logback-encoder` para logs estruturados e **Spring Boot Actuator** para monitoramento da saúde da aplicação.

---

Este projeto demonstra uma aplicação robusta, preparada para escalar e lidar com requisitos complexos de geolocalização e integração entre sistemas.

---

## 🛠️ Como Executar o Projeto

O projeto está configurado para rodar em containers, facilitando o setup do ambiente.

1. **Pré-requisitos:**
* Docker e Docker Compose instalados.
* Java 21 e Maven 3.8+ (opcional, para build local).


2. **Passo a Passo:**
* Certifique-se de que a rede `radares-net` existe no seu Docker:
```bash
docker network create radares-net

```


* No diretório raiz, execute o comando para subir os serviços (Banco de Dados, RabbitMQ e a Aplicação):
```bash
docker-compose up -d --build

```





## 📡 Endpoints Principais (API)

A documentação interativa completa (Swagger) pode ser acessada em `http://localhost:8085/swagger-ui.html` quando a aplicação estiver rodando. Abaixo, os principais recursos:

### Radares e Consultas

* `GET /radares/busca-placa`: Busca o histórico completo de passagens de uma placa específica (Otimizado com índices GIN).
* `GET /radares/busca-local`: Consulta operacional filtrada por data, rodovia, KM e sentido.
* `GET /radares/geo-search`: Busca geoespacial avançada. Retorna radares em um raio específico (metros) a partir de uma latitude/longitude.
* `GET /radares/all-locations`: Retorna todas as coordenadas para renderização no mapa (Utiliza Cache de 24h).

### Gestão de Domínios

* `GET /radares/rodovias`: Lista todas as rodovias cadastradas.
* `GET /radares/rodovias/{id}/kms`: Lista os marcos quilométricos vinculados a uma rodovia.

---

### Por que isso é importante para seu portfólio?

* **Docker:** Mostra que você sabe empacotar software de forma portável.
* **PostGIS:** Demonstra conhecimento em bancos de dados especializados (Geográficos), um diferencial raro no mercado.
* **Documentação:** Um README bem estruturado é a primeira coisa que recrutadores e professores olham em um repositório.


Este projeto demonstra uma aplicação robusta, preparada para escalar e lidar com requisitos complexos de geolocalização e integração entre sistemas.