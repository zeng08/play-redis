package play.cache

import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Success

import play.api.test._

import org.joda.time.DateTime
import org.specs2.execute.{AsResult, Result}
import org.specs2.matcher._
import org.specs2.mutable.Specification
import org.specs2.specification._

/**
 * <p>Test of cache to be sure that keys are differentiated, expires etc.</p>
 */
class CacheSpec extends Specification with AroundExample {

  lazy val invalidate = {
    // invalidate cache for test
    invoke inFuture Cache.invalidate( )
  }

  /** application context to perform operations in */
  protected def application = new FakeApplication( additionalPlugins = Seq(
    "play.api.libs.concurrent.AkkaPlugin",
    "play.cache.redis.RedisCachePlugin",
    "play.cache.redis.RedisCachePlugin20"
  ) )

  override def around[ T: AsResult ]( t: => T ): Result = {
    Helpers.running( application ) {
      // reload internal plugins
      Cache.reload()
      // ensure clear cache before first run
      invalidate
      // run in fake application to let cache working
      AsResult.effectively( t )
    }
  }

  implicit def inFuture[ T ]( value: Future[ T ] ): T = Await.result( value, Duration( "5s" ) )

  implicit def matcher[ T ]( matcher: Matcher[ T ] ): Matcher[ Future[ T ] ] = new Matcher[ Future[ T ] ] {
    override def apply[ S <: Future[ T ] ]( value: Expectable[ S ] ): MatchResult[ S ] = {
      val expectable: Expectable[ Future[ T ] ] = value
      val matched = expectable.map( inFuture[ T ] _ ).applyMatcher( matcher )
      result( matched, value )
    }
  }

  val invoke = this

  "Cache" should {

    "miss on get" in {
      Cache.get[ String ]( "test-key-1" ) must beNone
    }

    "hit after set" in {
      invoke inFuture Cache.set( "test-key-2", "value" )
      Cache.get[ String ]( "test-key-2" ) must beSome[ Any ]
      Cache.get[ String ]( "test-key-2" ) must beSome( "value" )
    }

    "miss after remove" in {
      invoke inFuture Cache.set( "test-key-3", "value" )
      Cache.get[ String ]( "test-key-3" ).isDefined must beTrue
      invoke inFuture Cache.remove( "test-key-3" )
      Cache.get[ String ]( "test-key-3" ) must beNone
    }

    "miss after timeout" in {
      // set
      invoke inFuture Cache.set( "test-key-4", "value", Some( 1 ) )
      Cache.get[ String ]( "test-key-4" ).isDefined must beTrue
      // wait until it expires
      Thread.sleep( 2000 )
      // miss
      Cache.get[ String ]( "test-key-4" ) must beNone
    }

    "miss at first getOrElse " in {
      val counter = new AtomicInteger( 0 )
      cachedValue( "test-key-5", counter ) must beSome( "value" )
      counter.get must beEqualTo( 1 )
    }

    "hit at second getOrElse" in {
      val counter = new AtomicInteger( 0 )
      for ( index <- 1 to 10 )
        cachedValue( "test-key-6", counter ) must beSome( "value" )
      counter.get must beEqualTo( 1 )
    }

    "distinct different keys" in {
      val counter = new AtomicInteger( 0 )
      cachedValue( "test-key-7A", counter ) must beSome( "value" )
      cachedValue( "test-key-7B", counter ) must beSome( "value" )
      counter.get must beEqualTo( 2 )
    }

    "perform future and store result" in {
      val counter = new AtomicInteger( 0 )
      // perform test
      for ( index <- 1 to 5 ) {
        Cache.getOrElse[ String ]( "test-key-8" )( orElse( counter ) ).map( _.toOption ) must beSome( "value" )

        // BUGFIX solution to synchronization issue. When this wasn't here,
        // the cache was synchronized a bit later and then it computed the
        // value twice, instead of just one. Adding this wait time it gives
        // a chance to cache to synchronize it
        Thread.sleep( 100 )
      }
      // verify
      counter.get must beEqualTo( 1 )
    }

    "propagate fail in future" in {
      val future = Future.failed( new IllegalStateException( "Exception in test." ) )
      invoke inFuture Cache.getOrElse[ String ]( "test-key-9" )( future ) must throwA( new IllegalStateException( "Exception in test." ) )
    }

    "support list" in {
      // store value
      invoke inFuture Cache.set( "list", List( "A", "B", "C" ) ) must beEqualTo( Success( "OK" ) )
      // recall
      val list = invoke inFuture Cache.get[ List[ String ] ]( "list" )
      list must beSome[ List[ String ] ]
      list must beSome( List( "A", "B", "C" ) )
    }

    "support a byte" in {
      invoke inFuture Cache.set( "type.byte", 0xAB.toByte )
      Cache.get[ Byte ]( "type.byte" ) must beSome[ Byte ]
      Cache.get[ Byte ]( "type.byte" ) must beSome( 0xAB.toByte )
    }

    "support a char" in {
      invoke inFuture Cache.set( "type.char.1", 'a' )
      Cache.get[ Char ]( "type.char.1" ) must beSome[ Char ]
      Cache.get[ Char ]( "type.char.1" ) must beSome( 'a' )
      invoke inFuture Cache.set( "type.char.2", 'b' )
      Cache.get[ Char ]( "type.char.2" ) must beSome( 'b' )
      invoke inFuture Cache.set( "type.char.3", 'č' )
      Cache.get[ Char ]( "type.char.3" ) must beSome( 'č' )
    }

    "support a short" in {
      invoke inFuture Cache.set( "type.short", 12.toShort )
      Cache.get[ Short ]( "type.short" ) must beSome[ Short ]
      Cache.get[ Short ]( "type.short" ) must beSome( 12.toShort )
    }

    "support an int" in {
      invoke inFuture Cache.set( "type.byte", 0xAB.toByte )
      Cache.get[ Byte ]( "type.byte" ) must beSome( 0xAB.toByte )
    }

    "support a long" in {
      invoke inFuture Cache.set( "type.long", 144L )
      Cache.get[ Long ]( "type.long" ) must beSome[ Long ]
      Cache.get[ Long ]( "type.long" ) must beSome( 144L )
    }

    "support a float" in {
      invoke inFuture Cache.set( "type.float", 1.23f )
      Cache.get[ Float ]( "type.float" ) must beSome[ Float ]
      Cache.get[ Float ]( "type.float" ) must beSome( 1.23f )
    }

    "support a double" in {
      invoke inFuture Cache.set( "type.double", 3.14 )
      Cache.get[ Double ]( "type.double" ) must beSome[ Double ]
      Cache.get[ Double ]( "type.double" ) must beSome( 3.14 )
    }

    "support a date" in {
      invoke inFuture Cache.set( "type.date", new Date( 123 ) )
      Cache.get[ Date ]( "type.date" ) must beSome[ Date ]
      Cache.get[ Date ]( "type.date" ) must beSome( new Date( 123 ) )
    }

    "support a datetime" in {
      invoke inFuture Cache.set( "type.datetime", new DateTime( 123456 ) )
      Cache.get[ DateTime ]( "type.datetime" ) must beSome[ DateTime ]
      Cache.get[ DateTime ]( "type.datetime" ) must beSome( new DateTime( 123456 ) )
    }

    "support a custom classes" in {
      invoke inFuture Cache.set( "type.object", SimpleObject( "B", 3 ) )
      Cache.get[ SimpleObject ]( "type.object" ) must beSome[ SimpleObject ]
      Cache.get[ SimpleObject ]( "type.object" ) must beSome( SimpleObject( "B", 3 ) )
    }

    "support a null" in {
      invoke inFuture Cache.set( "type.null", null )
      Cache.get[ SimpleObject ]( "type.null" ) must beNone
    }
  }

  protected def cachedValue( key: String, counter: AtomicInteger ): Future[ Option[ String ] ] =
    Cache.getOrElse[ String ]( key )( orElse( counter ) ).map( _.toOption )

  protected def orElse( counter: AtomicInteger ): Future[ String ] = {
    // access cached value
    // increment miss counter
    counter.incrementAndGet( )
    // return the value to cache
    Future.successful( "value" )
  }
}

case class SimpleObject( key: String, value: Int )
