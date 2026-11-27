# 서비스 명세·업무 절차·화면을 연결하는 AI 도우미 조사

조사일: 2026-11-27. 대상: DB Companion의 자연어 설정 도우미와 JSON 표시·편집 공통화. 아래 내용은 구현 전 공식 문서와 코드를 대조한 조사 기록이다. 이후 승인된 구현 범위와 검증 한계는 [기존 서비스를 사용하는 설정 도우미](in-process-service-tools.md), 실제 사용법은 [서비스 Tool 안내](../service-tools.md)에서 확인한다. 비교한 모든 제품을 설치·통합 실행한 결과를 의미하지 않는다.

## 사용자 설명에 따른 범위: 기존 서비스 직접 호출

사용자는 별도 MCP를 만들려는 것이 아니라, 이미 있는 서비스 계층에 역할·입출력 설명을 연결해 AI가 사용하도록 하려는 것이라고 명확히 했다. 이번 권장안은 **같은 애플리케이션 안에서 기존 Java 서비스 메서드를 호출하는 인프로세스 Tool Calling**이다. 아래 MCP 비교는 참고 자료이며 별도 MCP 서버·통신 계층을 이번 구현의 전제로 두지 않는다.

Spring AI는 메서드의 `@Tool`/`@ToolParam`, 코드에서 등록하는 `MethodToolCallback`, 함수·메서드 참조를 연결하는 `FunctionToolCallback`을 제공한다. 서비스 코드를 그대로 두고 별도의 등록 설정에서 설명과 호출 대상을 연결하는 방법도 있다. [Spring AI 도구 정의](https://docs.spring.io/spring-ai/reference/api/tools.html#_defining_tools).

권장 흐름: 업무 안내·절차와 등록된 서비스 설명 → AI의 작업 선택·인자 제안 → Java의 실제 로그인 문맥·입력 확인 → 기존 서비스 직접 호출 → 결과를 AI와 HTML 화면에 전달. 서비스의 검증·트랜잭션·권한·이력 처리를 재사용한다. 접속 세션 등 실행 문맥은 서버가 제공한다. DB LLM 텍스트 호출을 이 도구 실행 흐름과 연결하는 어댑터의 호환성은 별도 작은 시험 대상으로 유지한다.

서비스의 역할과 입력·출력을 명세하고, AI가 업무 절차와 실제 조회 결과를 참조해 다음 작업을 선택하는 방향은 적절하다. 권장 구성은 **서비스 계약 + 업무 지식·절차 + 실행 상태 + 화면 정의**를 연결하는 형태다. 각 명세가 공통 서비스 ID와 버전을 참조하게 하고, 기존 Java 서비스가 실제 작업을 수행한다. 이 결론과 도입 우선순위는 조사 결과를 DB Companion에 적용한 판단이다.

## 보통 어떻게 구성하는가

| 구성 | 담는 내용 | 사용하는 곳 |
|---|---|---|
| 서비스 계약 | 역할·사용 상황·입력/출력 Schema·파라미터 설명·예시·오류 | AI 도구 선택, 입력/출력 검사, 서비스 안내, MCP 어댑터 |
| 업무 지식 | 용어·집계 기준·업무 규칙·원문 근거와 버전 | 질의 설명과 SQL 생성의 의미 보완 |
| 업무 절차 | 목적·순서·분기·담당자·선행 조건·완료 확인 | 다음 단계 안내와 실행 관리 |
| 실행 상태 | 현재 대상·수집한 값·완료 단계·승인 대기·실제 결과 | 질문 이어가기, 중단/재개, 화면 동기화 |
| 화면 정의 | 라벨·그룹·순서·위젯·목록/표/비교/입력폼 | 사용자에게 읽기·편집 가능한 HTML 표시 |

서비스의 데이터 구조와 설명을 재사용해도, 업무의 순서나 화면 배치가 자동으로 모두 결정되지는 않는다. JSON Schema는 타입과 필수값 등을 정의하고, 화면의 그룹·순서 등은 별도 UI Schema로 연결하는 사례가 있다. [JSON Forms 개요](https://jsonforms.io/docs/), [UI Schema](https://jsonforms.io/docs/uischema/).

## MCP에 대응시키기

- **Tools**: 실제 서비스 조회·작업. 표준 도구 명세에는 이름·설명·`inputSchema`, 선택적인 `outputSchema`가 있으며, 구조화된 결과는 `structuredContent`로 전달한다. 앱 전용 선행 조건·실행 정책은 자체 계약으로 관리하고 MCP 정의에 필요한 정보를 투영한다. [MCP Tools 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/server/tools).
- **Resources**: 업무 설명·용어 정의·환경 설명 등 참조 자료를 전달하는 연결점.
- **Prompts**: 사용자가 선택해 시작하는 재사용 작업 템플릿. 등록만으로 매번 자동 참조되는 시스템 지침으로 취급하지 않는다. 필요한 절차를 선택·조회하고 모델 문맥에 넣는 동작은 애플리케이션이 마련한다. [MCP 서버 개념](https://modelcontextprotocol.io/docs/2026-07-28/learn/server-concepts).
- **실행 관리자**: 단계 의존 관계, 현재 상태, 승인 대기, 실패 후 재개와 완료 검증을 관리한다. MCP 도구 명세를 만드는 것과 이 실행 관리 기능을 구현하는 것은 서로 연결된 별도 작업이다.
- **MCP Apps**: 도구와 HTML UI 리소스를 연결하는 확장. 지원 호스트가 `ui://` 리소스를 받아 격리된 iframe에서 표시한다. 기존 앱 안의 HTML 렌더링에도 반드시 이 확장을 써야 하는 것은 아니며, 외부 호스트에 화면까지 제공할 때 별도로 검토한다. [MCP Apps 개요](https://apps.extensions.modelcontextprotocol.io/api/documents/overview.html).

## 모방할 솔루션과 범위

| 솔루션 | 확인한 패턴 | DB Companion 적용 판단 |
|---|---|---|
| Microsoft Copilot Studio | 도구·업무 주제·지식의 이름과 설명, 입출력을 바탕으로 생성형 오케스트레이션. 정해진 흐름은 Topic으로 구성 | 서비스 목록과 업무 절차를 어떻게 작성하고 AI가 선택하게 할지 참고할 1순위. 플랫폼 이전을 결정한 것은 아님 |
| CopilotKit | 팝업/사이드바, 앱과 에이전트 상태 연결, 도구별 결과 UI, 추가 입력·확인 후 재개 | 사용자가 요청한 앱 내 도우미의 상호작용을 모방할 1순위. 현재 Thymeleaf/일반 JS와 React 기반 SDK의 연결 비용은 별도 검토 |
| Spring AI | Java 도구·파라미터 설명, ToolCallback, 비모델 실행 문맥, MCP Server Starter | 현재 Java/Spring 서비스 계약·어댑터의 우선 검토 후보. DB 모델 호출 호환성은 별도 실증 필요 |
| LangChain4j | Java 메서드의 Tool/파라미터 설명을 명세로 전달하고 결과를 모델에 반환 | Java 대안 후보. Spring AI와 동시에 도입하기보다 작은 동일 시험으로 비교 |
| JSON Forms | 데이터 Schema와 UI Schema를 분리해 검증·표시·입력 화면 구성 | JSON을 업무용 HTML 요소로 표현할 때 참고. React/Angular/Vue 통합을 전제로 한 선택 비용 고려 |
| JSON Editor | JSON Schema에서 HTML 폼 생성, 여러 CSS 체계와 연결 | 기존 일반 JS 화면과의 연결 후보. 해당 프로젝트가 문서화한 Schema 지원 범위와 MCP의 Schema 버전을 대조해야 함 |
| Dify | 모델·지식 검색·도구·분기와 Human Input 노드에서 정지/입력/결정 후 진행 | 업무 전체 순서와 승인·실패 표시를 모방. 현재 앱의 서비스/인증/이력 전체를 외부 플랫폼으로 옮기는 결정은 보류 |
| LangGraph | 결정적 업무 흐름과 모델 선택의 결합, 체크포인트와 interrupt/resume | 장시간 실행·담당자 간 인계·서버 재시작 후 재개 요구가 커질 때 비교할 실행 계층 |

공식 근거:

- Copilot Studio: [생성형 오케스트레이션](https://learn.microsoft.com/en-us/microsoft-copilot-studio/guidance/generative-orchestration), [도구 등록과 입출력](https://learn.microsoft.com/en-us/microsoft-copilot-studio/add-tools-custom-agent), [업무 주제](https://learn.microsoft.com/en-us/microsoft-copilot-studio/authoring-create-edit-topics).
- CopilotKit: [Popup](https://docs.copilotkit.ai/reference/components/CopilotPopup), [Tool Rendering](https://docs.copilotkit.ai/generative-ui/tool-rendering), [Shared State](https://docs.copilotkit.ai/shared-state), [Human-in-the-loop](https://docs.copilotkit.ai/human-in-the-loop). 문서의 세대/버전별 API 경로가 섞여 있으므로 실제 도입 시 사용할 버전의 문서를 고정한다.
- Spring AI: [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html), [ChatModel](https://docs.spring.io/spring-ai/reference/api/chatmodel.html), [저수준 도구 호출](https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html), [MCP Server Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html).
- LangChain4j: [Tools](https://docs.langchain4j.dev/tutorials/tools/).
- JSON Editor: [공식 저장소](https://github.com/json-editor/json-editor). README는 JSON Schema v3/v4 지원을 명시한다. MCP 문서의 기본 Schema 2020-12와 같다고 가정하지 않는다.
- Dify: [Human Input](https://docs.dify.ai/en/cloud/use-dify/nodes/human-input), [Workflow Studio](https://www.dify.ai/workflows).
- LangGraph: [Workflows and agents](https://docs.langchain.com/oss/python/langgraph/workflows-agents), [Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts).

## 현재 코드와의 차이

2026-11-27 조사 시점의 파일을 읽어 확인했다.

| 현재 근거 | 확인한 내용 | 필요한 보완 |
|---|---|---|
| `service/AiSetupCatalog.java`, `setup/capabilities.json` | 목적·요건·실행 주체·작업·미리보기·검증 계약이 이미 존재 | 서비스별 입출력 Schema·예시·오류와 절차 ID/버전 연결 확장 |
| `service/AiSetupToolLoop.java` | 실제 등록된 조회 Tool은 3종. 대상/실행 주체를 서버가 고정해 모델 인자가 빈 객체 | 지원 작업별 명세와 핸들러를 늘리고, 실제 구현된 서비스만 실행 가능으로 노출 |
| `static/js/setup-workbench.mjs` | 명확한 명령은 정규식 분기, 다른 요청은 AI 분석. 페이지 메모리 상태 | 명령 분기에 계속 문구를 추가하기보다 서비스/절차 계약을 참조하는 선택과 상태 관리로 발전 |
| `repository/AiAssistantRepository.java` | JDBC로 `DBMS_CLOUD_AI.GENERATE` 호출 후 텍스트 결과를 사용 | 프레임워크가 요구하는 메시지·ToolCall·ToolResult로 연결하는 모델 어댑터를 실증 |
| `static/js/code-view.mjs` | 공통 PRE 관찰과 SQL 뷰어가 존재 | JSON 공통 표시의 재사용 지점. 입력 폼·업무별 결과 화면은 Schema/renderer 연결 필요 |

이미 있는 카탈로그와 기존 서비스를 재사용하는 것이 우선이다. 현재 도우미가 전체 업무 절차를 이해해 모든 서비스를 자율적으로 실행하는 상태라고 볼 근거는 없다.

## DB Companion 권장 구조

```text
대화창·현재 화면 문맥
          ↓
업무 지식·절차 선택 + 필요한 서비스 계약
          ↓
AI 해석·누락 정보 질문·다음 작업 제안
          ↓
Java 실행 관리자 → 현재 신원·입력·선행 조건 확인
          ↓
기존 Java 서비스 또는 연결된 외부 MCP
          ↓
구조화된 결과·진행 상태·변경 이력 참조
          ↓
등록된 HTML 컴포넌트 + 사용자에게 설명
```

아래는 설계 제안이다.

1. 서비스 ID·설명·입출력 Schema를 한 계약으로 관리하고 AI 도구 정의·파라미터 안내·검증에 재사용한다. 업무 절차와 UI 정의는 해당 ID를 참조한다. 한 파일에 모든 규칙과 레이아웃을 넣을 필요는 없다.
2. 업무 용어·집계 기준은 질의용 Semantic Context에, 앱 설정 순서·실행 규칙은 설정 도우미의 절차에 연결한다. 서로 다른 역할의 문맥을 용도에 맞게 제공한다.
3. AI는 목표 해석·설명·관련 도구 선택을 맡고, 반드시 지켜야 하는 순서와 완료 판정은 실행 관리자가 실제 상태로 확인한다. 로그인 신원·접속 정보·승인 권한은 서버 문맥에서 결정한다.
4. 조회·입력 대기·미리보기·사용자 확인 대기·적용·재조회·실패/결과 미확인을 구분한다. 설명과 실제 실행 결과를 같은 완료 상태로 묶지 않는다.
5. 결과 UI는 등록된 표·항목/값·입력폼·변경 비교 컴포넌트를 사용한다. 일반 JSON 뷰어와 원문 보기/복사는 공통 대체 화면으로 제공한다. AI가 임의 HTML을 만들어 실행하게 하는 구현을 기본으로 삼지 않는다.
6. 내부 도우미와 외부 MCP 어댑터는 같은 서비스 계약/핸들러를 재사용한다. 내부 Java 서비스 호출을 위해 반드시 네트워크 MCP 왕복을 추가할 필요는 없다. 외부 MCP 공개는 실제 프로토콜·인증·실행 신원을 갖춘 별도 어댑터 작업이다.
7. Java/Spring 실행 구조를 유지한 상태에서 Spring AI의 계약/도구 어댑터를 먼저 검증한다. DB의 텍스트 출력을 감싸는 것만으로 Native Tool Calling·스트리밍 지원이 생긴다고 가정하지 않는다. LangChain4j도 같은 기준의 비교 후보로 둔다.

## 먼저 검증할 한 업무

**“기존 업무 용어 사전을 이 프로필에 연결하고, 준비 상태와 변경 이력을 확인해 줘.”**

사전 상태·기존 콜백·활성 Source·대상 프로필 연결을 읽고 → 필요한 값만 질문 → 없는 선행 구성은 담당 작업으로 표시 → 연결 변경안 → 사용자 확인 → 기존 서비스 적용 → 실제 저장값·이력 재조회 → 검증된 결과로 설명.

이 업무는 조회, 누락 정보 질문, 단계 제약, 변경 확인, 기존 서비스 위임, 결과 화면을 함께 검증한다. 최초 검증에서 새 사전·콜백·Source 생성까지 한 번에 자동화할 필요는 없다. “설명만/변경하지 마”, 타 프로필·다른 사용자, 취소, 값/버전 변경, 같은 작업의 중복 실행, 쓰기 후 응답 유실, 페이지 이동 후 재개를 확인한다.

도입 판단은 이 한 업무에서 모델 어댑터 호환성·누락 인자 질문·실제 서비스 선택·UI 동기화·재개 상태를 확인한 뒤 내린다. 이번 조사는 설치·성능 측정·통합 시험을 포함하지 않는다.
