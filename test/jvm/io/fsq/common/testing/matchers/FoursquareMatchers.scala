// Copyright 2014 Foursquare Labs Inc. All Rights Reserved.

package io.fsq.common.testing.matchers

import io.fsq.common.scala.Identity._
import org.hamcrest.{BaseMatcher, Description, Matcher}
import scala.collection.TraversableLike
import scala.math.max


object FoursquareMatchers {
  private[matchers] class IsNone[T] extends BaseMatcher[Option[T]] {
    override def describeTo(d: Description) = d.appendText("Is not None")

    override def matches(other: Object) = other == None
  }

  def isNone[T]: Matcher[Option[T]] = new IsNone[T]()

  private[matchers] class EqualsCollection[A](expected: Iterable[A]) extends BaseMatcher[Iterable[A]] {
    private val MaxElementsToDisplay = 10

    override def describeTo(d: Description) = {
      d.appendText(s"equal to the collection: ${expected}")
    }

    override def describeMismatch(item: Object, d: Description): Unit = {
      val actual = item.asInstanceOf[Iterable[A]]
      val clauses = Vector.newBuilder[String]
      if (expected.size !=? actual.size) {
        clauses += s"collection lengths differed: expected.size=${expected.size}, actual.size=${actual.size}"
      }
      var numTotalDifferent = 0
      expected.zip(actual).zipWithIndex.foreach({
        case ((e, a), i) => {
          if (e !=? a) {
            numTotalDifferent += 1
            if (numTotalDifferent <= MaxElementsToDisplay) {
              clauses += s"element at index ${i} differed: expected=${e}, actual=${a}"
            }
          }
        }
      })
      val extraElementsThatWereDifferent = max(0, numTotalDifferent - MaxElementsToDisplay)
      if (extraElementsThatWereDifferent > 0) {
        clauses += s"${extraElementsThatWereDifferent} additional elements differed"
      }
      val descriptionString = clauses.result().mkString("; ")
      d.appendText(descriptionString)
    }

    override def matches(other: Object): Boolean = other == expected
  }

  /**
   * Hamcrest matcher that asserts a given Scala collection is equal to the provided collection,
   * and provides helpful error messages.
   *
   * Examples:
   *   assertThat(List(1, 2), equalsCollection(List(1, 2))) // Pass
   *   assertThat(List(2, 2), equalsCollection(List(1, 2))) // element at index 0 differed: expected=1, actual=2
   *   assertThat(List(1), equalsCollection(List(1, 2))) // collection lengths differed: expected.size=2, actual.size=1
   */
  def equalsCollection[A](expected: Iterable[A]): Matcher[Iterable[A]] = {
    new EqualsCollection(expected)
  }

  object IsNonEmpty extends BaseMatcher {
    override def describeTo(d: Description) = d.appendText("Not a non-empty collection")

    override def matches(other: Object) = {
      other match {
        case other: TraversableLike[_, _] => other.nonEmpty
        case _ => false
      }
    }
  }

  // TODO(patrick): If we ever update to hamcrest 1.3, we can use the existing matcher that does
  // this, but this is handy because it accepts scala collections instead of java collections
  class HasItem(matchers: Seq[Matcher[_]]) extends BaseMatcher {
    override def describeTo(d: Description) = {
      d.appendText("Didn't match any of:")
      matchers.foreach(m => m.describeTo(d))
    }

    override def matches(other: Object) = {
      other match {
        case other: TraversableLike[_, _] => other.exists(t => matchers.exists(m => m.matches(t)))
        case _ => false
      }
    }
  }

  // TODO(patrick): If we ever update to hamcrest 1.3, we can use the existing matcher that does
  // this, but this is handy because it accepts scala collections instead of java collections
  class ContainsInAnyOrder(matchers: Seq[Matcher[_]]) extends BaseMatcher {
    override def describeTo(d: Description) = {
      d.appendText("Didn't match in any order:")
      matchers.foreach(m => m.describeTo(d))
    }

    override def matches(other: Object) = {
      other match {
        case other: TraversableLike[_, _] => other.forall(t => matchers.exists(m => m.matches(t)))
        case _ => false
      }
    }
  }
}

