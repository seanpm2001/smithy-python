from dataclasses import dataclass
from typing import Any, Protocol, TypeVar

# Defining headers as a list instead of a mapping to avoid ambiguity and
# the nuances of multiple fields in a mapping style interface
HeadersList = list[tuple[str, str]]


class URL(Protocol):
    scheme: str  # http or https
    hostname: str  # hostname e.g. amazonaws.com
    port: int | None  # explicit port number
    path: str  # request path
    query_params: list[tuple[str, str]]


class Request(Protocol):
    url: URL
    method: str  # GET, PUT, etc
    headers: HeadersList
    body: Any


class Response(Protocol):
    status_code: int  # HTTP status code
    headers: HeadersList
    body: Any


class Endpoint(Protocol):
    url: URL
    headers: HeadersList


# EndpointParams are defined in the generated client, so we use a TypeVar here.
# More specific EndpointParams implementations are subtypes of less specific ones. But
# consumers of less specific EndpointParams implementations are subtypes of consumers
# of more specific ones.
EndpointParams = TypeVar("EndpointParams", contravariant=True)


class EndpointResolver(Protocol[EndpointParams]):
    """Resolves an operation's endpoint based given parameters."""

    async def resolve_endpoint(self, params: EndpointParams) -> Endpoint:
        raise NotImplementedError()


@dataclass(kw_only=True)
class HttpRequestConfiguration:
    """Request-level HTTP configuration.

    :param read_timeout: How long, in seconds, the client will attempt to read the
    first byte over an established, open connection before timing out.
    """

    read_timeout: float | None = None


class HttpClient(Protocol):
    """A synchronous HTTP client interface."""

    def send(
        self, request: Request, request_config: HttpRequestConfiguration
    ) -> Response:
        pass


class AsyncHttpClient(Protocol):
    """An asynchronous HTTP client interface."""

    async def send(
        self, request: Request, request_config: HttpRequestConfiguration
    ) -> Response:
        pass
