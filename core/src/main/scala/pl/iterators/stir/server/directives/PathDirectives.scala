package pl.iterators.stir.server.directives

import org.http4s.Status
import org.http4s.dsl.io.Path
import pl.iterators.stir.server._

trait PathDirectives extends PathMatchers with ImplicitPathMatcherConstruction {
  import BasicDirectives._
  import RouteDirectives._
  import PathMatcher._

  /**
   * Applies the given [[PathMatcher]] to the remaining unmatched path after consuming a leading slash.
   * The matcher has to match the remaining path completely.
   * If matched the value extracted by the [[PathMatcher]] is extracted on the directive level.
   *
   * @group path
   */
  def path[L](pm: PathMatcher[L]): Directive[L] = pathPrefix(pm ~ PathEnd)

  /**
   * Applies the given [[PathMatcher]] to a prefix of the remaining unmatched path after consuming a leading slash.
   * The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   *
   * @group path
   */
  def pathPrefix[L](pm: PathMatcher[L]): Directive[L] = rawPathPrefix(Slash ~ pm)

  /**
   * Applies the given matcher directly to a prefix of the unmatched path of the
   * [[RequestContext]] (i.e. without implicitly consuming a leading slash).
   * The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   *
   * @group path
   */
  def rawPathPrefix[L](pm: PathMatcher[L]): Directive[L] = {
    implicit val LIsTuple = pm.ev
    extract(ctx => pm(ctx.unmatchedPath)).flatMap {
      case Matched(rest, values) =>
        tprovide(values)(LIsTuple) & mapRequestContext(ctx => ctx.copy(unmatchedPath = rest))
      case Unmatched => reject
    }
  }

  /**
   * Checks whether the unmatchedPath of the [[RequestContext]] has a prefix matched by the
   * given PathMatcher. In analogy to the `pathPrefix` directive a leading slash is implied.
   *
   * @group path
   */
  def pathPrefixTest[L](pm: PathMatcher[L]): Directive[L] = rawPathPrefixTest(Slash ~ pm)

  /**
   * Checks whether the unmatchedPath of the [[RequestContext]] has a prefix matched by the
   * given PathMatcher. However, as opposed to the `pathPrefix` directive the matched path is not
   * actually "consumed".
   *
   * @group path
   */
  def rawPathPrefixTest[L](pm: PathMatcher[L]): Directive[L] = {
    implicit val LIsTuple = pm.ev
    extract(ctx => pm(ctx.unmatchedPath)).flatMap {
      case Matched(_, values) => tprovide(values)
      case Unmatched          => reject
    }
  }

  /**
   * Applies the given [[PathMatcher]] to a suffix of the remaining unmatchedPath of the [[RequestContext]].
   * If matched the value extracted by the [[PathMatcher]] is extracted and the matched parts of the path are consumed.
   * Note that, for efficiency reasons, the given [[PathMatcher]] must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffix("baz" / "bar")` would match `/foo/bar/baz`!
   *
   * @group path
   */
  def pathSuffix[L](pm: PathMatcher[L]): Directive[L] = {
    implicit val LIsTuple = pm.ev
    extract(ctx =>
      pm(Path(ctx.unmatchedPath.segments.reverse, ctx.unmatchedPath.endsWithSlash,
        ctx.unmatchedPath.absolute))).flatMap {
      case Matched(rest, values) =>
        tprovide(values)(LIsTuple) & mapRequestContext(ctx =>
          ctx.copy(unmatchedPath = Path(rest.segments.reverse, rest.endsWithSlash,
            rest.absolute)))
      case Unmatched => reject
    }
  }

  /**
   * Checks whether the unmatchedPath of the [[RequestContext]] has a suffix matched by the
   * given PathMatcher. However, as opposed to the pathSuffix directive the matched path is not
   * actually "consumed".
   * Note that, for efficiency reasons, the given PathMatcher must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffixTest("baz" / "bar")` would match `/foo/bar/baz`!
   *
   * @group path
   */
  def pathSuffixTest[L](pm: PathMatcher[L]): Directive[L] = {
    implicit val LIsTuple = pm.ev
    extract(ctx =>
      pm(Path(ctx.unmatchedPath.segments.reverse, ctx.unmatchedPath.endsWithSlash,
        ctx.unmatchedPath.absolute))).flatMap {
      case Matched(_, values) => tprovide(values)
      case Unmatched          => reject
    }
  }

  /**
   * Rejects the request if the unmatchedPath of the [[RequestContext]] is non-empty,
   * or said differently: only passes on the request to its inner route if the request path
   * has been matched completely.
   *
   * @group path
   */
  def pathEnd: Directive0 = rawPathPrefix(PathEnd)

  /**
   * Only passes on the request to its inner route if the request path has been matched
   * completely or only consists of exactly one remaining slash.
   *
   * Note that trailing slash and non-trailing slash URLs are '''not''' the same, although they often serve
   * the same content. It is recommended to serve only one URL version and make the other redirect to it using
   * [[redirectToTrailingSlashIfMissing]] or [[redirectToNoTrailingSlashIfPresent]] directive.
   *
   * For example:
   * {{{
   * def route = {
   *   // redirect '/users/' to '/users', '/users/:userId/' to '/users/:userId'
   *   redirectToNoTrailingSlashIfPresent(Found) {
   *     pathPrefix("users") {
   *       concat(
   *         pathEnd {
   *           // user list ...
   *         },
   *         path(UUID) { userId =>
   *           // user profile ...
   *         }
   *       )
   *     }
   *   }
   * }
   * }}}
   *
   * For further information, refer to:
   *
   * @see [[https://webmasters.googleblog.com/2010/04/to-slash-or-not-to-slash.html]]
   * @group path
   */
  def pathEndOrSingleSlash: Directive0 = rawPathPrefix(Slash.? ~ PathEnd)

  /**
   * Only passes on the request to its inner route if the request path
   * consists of exactly one remaining slash.
   *
   * @group path
   */
  def pathSingleSlash: Directive0 = pathPrefix(PathEnd)

  /**
   * If the request path doesn't end with a slash, redirect to the same uri with trailing slash in the path.
   *
   * '''Caveat''': [[path]] without trailing slash and [[pathEnd]] directives will not match inside of this directive.
   *
   * @group path
   */
  def redirectToTrailingSlashIfMissing(redirectionType: Status): Directive0 =
    extractUri.flatMap { uri =>
      if (uri.path.endsWithSlash) pass
      else {
        val newPath = uri.path.addEndsWithSlash
        val newUri = uri.withPath(newPath)
        redirect(newUri, redirectionType)
      }
    }

  /**
   * If the request path ends with a slash, redirect to the same uri without trailing slash in the path.
   *
   * Note, however, that this directive doesn't apply to a URI consisting of just a single slash.
   * HTTP does not support empty target paths, so that browsers will convert
   * a URI such as `http://example.org` to `http://example.org/` adding the trailing slash.
   *
   * Redirecting the single slash path URI would lead to a redirection loop.
   *
   * '''Caveat''': [[pathSingleSlash]] directive will only match on the root path level inside of this directive.
   *
   * @group path
   */
  def redirectToNoTrailingSlashIfPresent(redirectionType: Status): Directive0 =
    extractUri.flatMap { uri =>
      if (uri.path.endsWithSlash && !uri.path.isEmpty) {
        redirect(uri.withPath(uri.path.dropEndsWithSlash), redirectionType)
      } else pass
    }

  /**
   * Tries to match the inner route and if it fails with an empty rejection, it tries it again
   * adding (or removing) the trailing slash on the given path.
   *
   * @group path
   */

  /**
   * Tries to match the inner route and if it fails with an empty rejection, it tries it again
   * adding (or removing) the trailing slash on the given path.
   *
   * @group path
   */
  def ignoreTrailingSlash: Directive0 = Directive[Unit] {
    import PathDirectives._

    /**
     * Converts a URL that ends with `/` to one without. Or one that ends without `/` to one with it.
     */
    def flipTrailingSlash(path: Path): Path = {
      if (path.endsWithSlash) path.dropEndsWithSlash else path.addEndsWithSlash
    }

    /**
     * Transforms empty rejections to [[PathDirectives.TrailingRetryRejection]]
     * for the only purpose to break the loop of rejection handling
     */
    val transformEmptyRejections = recoverRejections(rejections =>
      if (rejections == Nil) {
        RouteResult.Rejected(List(TrailingRetryRejection))
      } else RouteResult.Rejected(rejections))

    inner =>
      import ExecutionDirectives._
      val totallyMissingHandler = RejectionHandler.newBuilder()
        .handleNotFound {
          mapRequestContext(ctx => ctx.copy(unmatchedPath = flipTrailingSlash(ctx.unmatchedPath))) {
            // transforming the rejection to break the loop.
            transformEmptyRejections {
              inner(())
            }
          }
        }
        .result()

      cancelRejection(TrailingRetryRejection) {
        handleRejections(totallyMissingHandler) {
          inner(())
        }
      }
  }
}

object PathDirectives extends PathDirectives {
  private[stir] case object TrailingRetryRejection extends Rejection
}
