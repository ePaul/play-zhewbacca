package org.zalando.zhewbacca

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.http.Status.FORBIDDEN
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{RequestHeader, Results}
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.{ExecutionContext, Future}

class SecurityRuleSpec(implicit ec: ExecutionContext) extends Specification with Mockito {
  sequential

  private val testTokenInfo = TokenInfo("", Scope.Default, "token-type", "test-user-id")
  private def authProvider(expectedResult: AuthResult): AuthProvider = {
    val provider = mock[AuthProvider]
    provider.valid(any[Option[OAuth2Token]], any[Scope]) returns Future.successful(expectedResult)
  }

  "ValidateTokenRule" should {
    "be applicable to specific route" in {
      val rule = ValidateTokenRule(mock[AuthProvider], "GET", "/api/.*", Scope.Default)
      rule.isApplicableTo(FakeRequest("GET", "/api/foo")) must beTrue
      rule.isApplicableTo(FakeRequest("GET", "/api/foo?a=b")) must beTrue
      rule.isApplicableTo(FakeRequest("GET", "/api/")) must beTrue

      rule.isApplicableTo(FakeRequest("GET", "/api")) must beFalse
      rule.isApplicableTo(FakeRequest("PUT", "/api/foo")) must beFalse
      rule.isApplicableTo(FakeRequest("GET", "/api2")) must beFalse
    }

    "inject TokenInfo into authenticated request" in {
      import TokenInfoConverter._

      val request = FakeRequest("GET", "/")

      val rule = ValidateTokenRule(authProvider(AuthTokenValid(testTokenInfo)), "GET", "/", Scope.Default)
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok(request.tokenInfo.userUid)) }

      contentAsString(rule.execute(nextFilter, request)) must beEqualTo("test-user-id")
    }

    "return error for non-authenticated request" in {
      val request = FakeRequest("GET", "/")
      val rule = ValidateTokenRule(authProvider(AuthTokenInvalid), "GET", "/", Scope.Default)
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok) }

      status(rule.execute(nextFilter, request)) must beEqualTo(UNAUTHORIZED)
    }
  }

  "ExplicitlyAllowedRule" should {
    "pass unmodified request to next filter" in {
      val testAttribute: TypedKey[String] = TypedKey("testAttribute")
      val originalRequest = FakeRequest("GET", "/foo").addAttr(testAttribute, "testValue")
      val rule = ExplicitlyAllowedRule("GET", "/foo")
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok(request.attrs(testAttribute))) }

      contentAsString(rule.execute(nextFilter, originalRequest)) must beEqualTo("testValue")
    }

    "be applicable to specific request" in {
      val rule = ExplicitlyAllowedRule("GET", "/foo.*")

      rule.isApplicableTo(FakeRequest("GET", "/foo/bar")) must beTrue
      rule.isApplicableTo(FakeRequest("GET", "/bar/foo")) must beFalse
    }
  }

  "ExplicitlyDeniedRule" should {

    "be applicable to specific request" in {
      val rule = ExplicitlyDeniedRule(authProvider(AuthTokenInvalid), "GET", "/foo.*")

      rule.isApplicableTo(FakeRequest("GET", "/foo/bar")) must beTrue
      rule.isApplicableTo(FakeRequest("GET", "/bar/foo")) must beFalse
    }

    "reject a request and respond with 401 HTTP status in case no token" in {
      val rule = ExplicitlyDeniedRule(authProvider(AuthTokenEmpty), "GET", "/foo")
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok) }

      status(rule.execute(nextFilter, FakeRequest())) must beEqualTo(UNAUTHORIZED)
    }

    "reject a request and respond with 403 HTTP status in case valid token" in {
      val rule = ExplicitlyDeniedRule(authProvider(AuthTokenValid(testTokenInfo)), "GET", "/foo")
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok) }

      status(rule.execute(nextFilter, FakeRequest())) must beEqualTo(FORBIDDEN)
    }

  }

  "DenyAllRule" should {

    "be applicable to all requests" in {
      val rule = DenyAllRule(authProvider(AuthTokenInvalid))
      rule.isApplicableTo(FakeRequest()) must beTrue
    }

    "reject a request and respond with 401 HTTP status in case empty token" in {
      val rule = DenyAllRule(authProvider(AuthTokenEmpty))
      val nextFilter = { _: RequestHeader => Future.successful(Results.Ok) }

      status(rule.execute(nextFilter, FakeRequest())) must beEqualTo(UNAUTHORIZED)
    }

    "reject a request and respond with 401 HTTP status in case token is invalid " in {
      val rule = DenyAllRule(authProvider(AuthTokenInvalid))
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok) }

      status(rule.execute(nextFilter, FakeRequest())) must beEqualTo(UNAUTHORIZED)
    }

    "reject a request and respond with 403 HTTP status in case valid token " in {
      val rule = DenyAllRule(authProvider(AuthTokenValid(testTokenInfo)))
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok) }

      status(rule.execute(nextFilter, FakeRequest())) must beEqualTo(FORBIDDEN)
    }

    "reject a request and respond with 403 HTTP status in case" in {
      val rule = DenyAllRule(authProvider(AuthTokenInsufficient))
      val nextFilter = { request: RequestHeader => Future.successful(Results.Ok) }

      status(rule.execute(nextFilter, FakeRequest())) must beEqualTo(FORBIDDEN)
    }

  }

}
