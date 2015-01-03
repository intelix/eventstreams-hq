package eventstreams.filetailer

import _root_.core.events.EventOps.symbolToEventField
import akka.actor.ActorSystem
import eventstreams.ds.plugins.filetailer.FileTailerConstants
import eventstreams.ds.plugins.filetailer.FileTailerConstants._
import eventstreams.support.ActorTestContext
import eventstreams.support.SinkStubActor._
import play.api.libs.json.{JsValue, Json}

class FileTailerDatasourceTest(_system: ActorSystem)
  extends ActorTestContext(_system)
  with FileTailerTestSupport {

  def this() = this(ActorSystem("TestSystem"))


  "FileTailerDatasource" must {

    "be built with valid config" in new WithBasicConfig {
      shouldBuild()
    }

    s"not be built if $CfgFDirectory is missing" in new WithBasicConfig {
      override def config: JsValue = Json.obj(
        CfgFMainPattern -> "mylog.txt",
        CfgFRolledPattern -> ".*gz"
      )

      shouldNotBuild()
    }

    s"not be built if $CfgFMainPattern is missing" in new WithBasicConfig {
      override def config: JsValue = Json.obj(
        CfgFDirectory -> "f:/tmp/log",
        CfgFRolledPattern -> ".*gz"
      )

      shouldNotBuild()
    }

    s"not be built if $CfgFMainPattern is invalid" in new WithBasicConfig {
      override def config: JsValue = Json.obj(
        CfgFDirectory -> "f:/tmp/log",
        CfgFMainPattern -> "(gz"
      )

      shouldNotBuild()
    }

    s"not be built if $CfgFRolledPattern is invalid" in new WithBasicConfig {
      override def config: JsValue = Json.obj(
        CfgFDirectory -> "f:/tmp/log",
        CfgFMainPattern -> ".+gz",
        CfgFRolledPattern -> "("
      )

      shouldNotBuild()
    }

    s"not be built if $CfgFBlockSize is less than 32" in new WithBasicConfig {
      override def config: JsValue = Json.obj(
        CfgFDirectory -> "f:/tmp/log",
        CfgFMainPattern -> ".+gz",
        CfgFBlockSize -> 10
      )

      shouldNotBuild()
    }

    "be a new instance when added to the flow" in new WithBasicConfig {
      withDatasourceFlow {
        expectSomeEvents(DatasourceInstance)
      }
    }

    "initially be stopped" in new WithBasicConfig {
      withDatasourceFlow {
        waitAndCheck {
          expectNoEvents(FileTailerConstants.Starting)
        }
      }
    }

    "propagate demand to the publisher" in new WithBasicConfig {
      withDatasourceFlow {
        flowCtx.foreach(activateSink)
        expectSomeEvents(FileTailerConstants.NewDemand)
      }
    }

    "when built and ready to be started" must {

      "activate on request" in new WithBasicConfig {
        withDatasourceFlow {
          flowCtx.foreach(activateFlow)
          expectSomeEvents(FileTailerConstants.Starting)
        }
      }

      "not produce any messages if activated and directory is empty" in new WithBasicConfig {
        withDatasourceFlow {
          flowCtx.foreach(activateFlow)
          waitAndCheck {
            expectNoEvents(FileTailerConstants.MessagePublished)
          }
        }
      }

      "when started in directory with a single empty log file" must {

        "not produce message initially" in new EmptyDirWithDemand {
          runWithNewFile { f =>
            waitAndCheck {
              expectNoEvents(FileTailerConstants.MessagePublished)
            }
          }
        }

        "not produce any messages if directory is wrong" in new EmptyDirWithDemand {
          override def config: JsValue = Json.obj(
            CfgFDirectory -> "/zzz/zzzzz",
            CfgFMainPattern -> "current[.]log$",
            CfgFRolledPattern -> "current-.+",
            CfgFBlockSize -> testBlockSize,
            CfgFStartWith -> "first"
          )

          runWithNewFile { f =>
            waitAndCheck {
              expectNoEvents(FileTailerConstants.MessagePublished)
            }
            expectSomeEvents(FileTailerConstants.Error)
          }
        }

        "produce message when first file gets some data" in new EmptyDirWithDemand {
          runWithNewFile { f =>
            f.write("line1")
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "line1")
          }
        }

        "produce a single message regardless of how many lines are in the file" in new EmptyDirWithDemand {
          runWithNewFile { f =>
            f.write("line1\nline2\nline3")
            expectSomeEvents(1, ReceivedMessageAtSink)
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "line1\nline2\nline3")
          }
        }

        "produce two messages as per blocksize config" in new EmptyDirWithDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "BBB")
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "BBB")
          }
        }

        "produce two messages as per blocksize config - taking all characters into account" in new EmptyDirWithDemand {
          runWithNewFile { f =>
            f.write(("A" * (testBlockSize - 1) + "\n") + "BBB")
            expectSomeEvents(2, ReceivedMessageAtSink)
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * (testBlockSize - 1) + "\n"))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "BBB")
          }
        }

        "produce two messages as per blocksize config - taking all characters into account 2" in new EmptyDirWithDemand {
          runWithNewFile { f =>
            f.write(("A" * testBlockSize + "\n") + "BBB")
            expectSomeEvents(2, ReceivedMessageAtSink)
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "\nBBB")
          }
        }

        "produce no messages if there is no demand" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            flowCtx.foreach(setSinkRequestStrategyManual)
            f.write("A" * testBlockSize + "BBB")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
          }
        }

        "produce a single message as per demand" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "BBB")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
            flowCtx.foreach(sinkProduceDemand(1, _))
            waitAndCheck {
              expectSomeEvents(1, ReceivedMessageAtSink)
            }
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
          }
        }

        "produce a single message followed by another message as per demand" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
            flowCtx.foreach(sinkProduceDemand(1, _))
            waitAndCheck {
              expectSomeEvents(1, ReceivedMessageAtSink)
            }
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(1, _))
            waitAndCheck {
              expectSomeEvents(1, ReceivedMessageAtSink)
            }
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
          }
        }
        "produce a single message followed by another two messages as per demand" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
            flowCtx.foreach(sinkProduceDemand(1, _))
            waitAndCheck {
              expectSomeEvents(1, ReceivedMessageAtSink)
            }
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(2, _))
            waitAndCheck {
              expectSomeEvents(2, ReceivedMessageAtSink)
            }
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "CCC")
          }
        }

        "produce a single message followed by another two messages as per demand - demanding more than available" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
            flowCtx.foreach(sinkProduceDemand(1, _))
            waitAndCheck {
              expectSomeEvents(1, ReceivedMessageAtSink)
            }
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(10, _))
            waitAndCheck {
              expectSomeEvents(2, ReceivedMessageAtSink)
            }
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "CCC")
          }
        }


        "get remainder from the rolled file and keep reading from the main file if enough demand" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("DDD")
            flowCtx.foreach(sinkProduceDemand(10, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "DDD")
          }
        }

        "get remainder from the rolled file but only up to the demand" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("DDD")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "DDD")
            }

          }
        }

        "get remainder from the rolled file but only up to the demand - additional demand should get the final remaining block from the rolled file" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("DDD")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "DDD")
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "DDD")
            }


          }
        }

        "get remainder from the rolled file but only up to the demand - additional demand should get the final remaining block from the rolled file and another demand should get the main contents" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("DDD")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "DDD")
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "DDD")
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "DDD")
          }
        }

        "handle multiple rolled files when demand requested - one by one with checkpoints" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
          }
        }

        "handle multiple rolled files when demand requested - by blocks of two" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(2, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(2, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "FFF")
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(2, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "III")
            }
          }
        }

        "handle multiple rolled files when demand requested - all at once" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(20, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
          }
        }


        "handle multiple rolled files when demand requested - rolled again while still on 1st rolled file" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.rollGz("current-3.gz")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
          }
        }

        "handle multiple rolled files when demand requested - rolled again while still on 1st rolled file - getting all remaining on second demand" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.rollGz("current-3.gz")
            flowCtx.foreach(sinkProduceDemand(20, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
          }
        }

        "handle multiple rolled files when demand requested - rolled again while still on 1st rolled file - getting all remaining on second demand - plus some extra into main" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.rollGz("current-3.gz")
            flowCtx.foreach(sinkProduceDemand(20, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")
          }
        }

        "handle multiple rolled files when demand requested - rolled again while still on 1st rolled file - getting all remaining on second demand - except for last one" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.rollGz("current-3.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
          }
        }

        "handle multiple rolled files when demand requested - rolled again while still on 1st rolled file - getting all remaining on second demand - except for last one - rolled and produced demand to get the rest" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-2.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.rollGz("current-3.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
            clearEvents()
            f.rollGz("current-4.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")

          }
        }

        "handle multiple rolled files when demand requested - like previous but rolling with random filenames" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-Z.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-Y.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.rollGz("current-X.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
            clearEvents()
            f.rollGz("current-A.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")

          }
        }

        "handle multiple rolled files when demand requested - rolling before consuming all input from the current file" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "BBB")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-Z.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-Y.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))


            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "BBB")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
          }
        }

        "handle multiple rolled files when demand requested - like previous but deleting old files before rolling" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "BBB")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-Z.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-Y.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> "BBB")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.delete("current-Z.gz")
            f.rollGz("current-X.gz")
            flowCtx.foreach(sinkProduceDemand(8, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
            clearEvents()
            f.delete("current-Y.gz")
            f.delete("current-X.gz")
            f.rollGz("current-A.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")

          }
        }

        "handle multiple rolled files when demand requested - like previous but deleting old files before rolling - with one missing block" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-Z.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-Y.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")

            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            flowCtx.foreach(deactivateFlow)
            f.delete("current-Z.gz")
            flowCtx.foreach(activateFlow)
            flowCtx.foreach(sinkProduceDemand(8, _))
            expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.rollGz("current-X.gz")
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
            clearEvents()
            f.delete("current-Y.gz")
            f.delete("current-X.gz")
            f.rollGz("current-A.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")

          }
        }

        "handle multiple rolled files when demand requested - like previous but deleting old files before rolling - with one missing block - without flow deactivation" in new EmptyDirWithoutDemand {
          override def config: JsValue = Json.obj(
            CfgFDirectory -> tempDirPath,
            CfgFMainPattern -> "current[.]log$",
            CfgFRolledPattern -> "current-.+",
            CfgFBlockSize -> testBlockSize,
            CfgFInactivityThresholdMs -> 500
          )

          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollGz("current-Z.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-Y.gz")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")

            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.delete("current-Z.gz")
            flowCtx.foreach(sinkProduceDemand(8, _))
            expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.rollGz("current-X.gz")
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
            clearEvents()
            f.delete("current-Y.gz")
            f.delete("current-X.gz")
            f.rollGz("current-A.gz")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")

          }
        }

        "handle multiple rolled files when demand requested - like previous but rolling with open files instead of compressed" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            f.rollOpen("current-Z.log")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollOpen("current-Y.log")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            f.rollOpen("current-X.log")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
            clearEvents()
            f.rollOpen("current-A.log")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")

          }
        }

        "handle multiple rolled files when demand requested - like previous but deactivating flow in between" in new EmptyDirWithoutDemand {
          runWithNewFile { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            clearEvents()
            flowCtx.foreach(deactivateFlow)
            f.rollOpen("current-1.log")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollOpen("current-2.log")
            f.write("G" * testBlockSize + "H" * testBlockSize + "III")
            flowCtx.foreach(activateFlow)
            flowCtx.foreach(sinkProduceDemand(1, _))
            expectSomeEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            }
            clearEvents()
            flowCtx.foreach(deactivateFlow)
            f.rollOpen("current-3.log")
            flowCtx.foreach(activateFlow)
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("G" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("H" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "III")
            clearEvents()
            f.write("X" * testBlockSize + "Y" * testBlockSize + "ZZZ")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("X" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("Y" * testBlockSize))
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> "ZZZ")
            }
            clearEvents()
            f.rollOpen("current-4.log")
            flowCtx.foreach(sinkProduceDemand(9, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "ZZZ")

          }
        }
      }


      "when started in a non-empty directory with sorting option configured as only by name" must {

        "consume all contents in the correct order regardless of the timestamps" in new EmptyDirWithoutDemandDeactivatedOrderByNameOnly {
          run { f =>
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("FFF")
            flowCtx.foreach(activateFlow)
            flowCtx.foreach(sinkProduceDemand(3, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            flowCtx.foreach(sinkProduceDemand(3, _))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

      }

      "when started in a non-empty directory with starting from configured as 'first'" must {

        "consume all contents of a single file if there is demand" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
          }
        }

        "consume all contents of a single file if there is demand, and keep watching the file" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - handle if it is rolled after activation" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - handle if it is rolled after activation with some additional input" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-1.gz")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - as previous but some extra data into a main file" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-1.gz")
            f.write("FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - handle if it is rolled before activation" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - handle if it is rolled before activation with some additional input" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
          }
        }

        "consume all contents if there is demand, and keep watching the file - rolled before activation - as previous but some extra data into a main file" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - rolled before activation - as previous but multiple rolled files - no main file" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            f.rollGz("current-3.gz")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - rolled before activation - as previous but multiple rolled files - no main file - keep watching the file" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            f.rollGz("current-3.gz")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            clearEvents()
            f.write("GGG")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "GGG")
          }
        }

        "consume all contents if there is demand, and keep watching the file - rolled before activation - as previous but multiple rolled files - with main file" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - rolled before activation - as previous but multiple rolled files - with main file - keep watching the file" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            clearEvents()
            f.write("GGG")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "GGG")
          }
        }

        "consume all rolled content and all new content, with rolling gz files (activated on 5th file), consistently" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            (1 to 50) foreach { i =>
              f.rollGz("current-" + i + ".gz")
              f.write("A" * testBlockSize + "B" * testBlockSize + "CCC" + i.toString)
              if (i == 5) {
                flowCtx.foreach(activateFlow)
                Thread.sleep(1000)
              }
              if (i == 15) {
                Thread.sleep(1000)
              }
              if (i == 25) {
                Thread.sleep(1000)
              }
            }
            Thread.sleep(5000)
            (1 to 50) foreach { i =>
              expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("CCC" + i.toString))
            }
            expectSomeEventsWithTimeout(10000, 50, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(50, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
          }
        }

        "consume all rolled content and all new content, with rolling open files (activated on 5th file), consistently" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            (1 to 50) foreach { i =>
              f.rollOpen("current-" + i + ".log")
              f.write("A" * testBlockSize + "B" * testBlockSize + "CCC" + i.toString)
              if (i == 5) {
                flowCtx.foreach(activateFlow)
                Thread.sleep(1000)
              }
              if (i == 15) {
                Thread.sleep(1000)
              }
              if (i == 25) {
                Thread.sleep(1000)
              }
            }
            Thread.sleep(5000)
            (1 to 50) foreach { i =>
              expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("CCC" + i.toString))
            }
            expectSomeEventsWithTimeout(10000, 50, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(50, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
          }
        }


        "consume all rolled content and all new content, with rolling gz files (activated on 5th file, deactivated on 15th, reactivated on 25th), consistently" in new EmptyDirWithDemandDeactivatedStartWithFirst {
          run { f =>
            (1 to 50) foreach { i =>
              f.rollGz("current-" + i + ".gz")
              f.write("A" * testBlockSize + "B" * testBlockSize + "CCC" + i.toString)
              if (i == 5) {
                flowCtx.foreach(activateFlow)
                Thread.sleep(1000)
              }
              if (i == 15) {
                flowCtx.foreach(deactivateFlow)
                Thread.sleep(1000)
              }
              if (i == 25) {
                flowCtx.foreach(activateFlow)
                Thread.sleep(1000)
              }
            }
            Thread.sleep(5000)
            (1 to 50) foreach { i =>
              expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("CCC" + i.toString))
            }
            expectSomeEventsWithTimeout(10000, 50, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(50, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
          }
        }


      }



      "when started in a non-empty directory with starting from configured as 'last'" must {

        "consume all contents of a single file if there is demand" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
          }
        }

        "consume all contents of a single file if there is demand, and keep watching the file" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - handle if it is rolled after activation" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - handle if it is rolled after activation with some additional input" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            f.rollGz("current-1.gz")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - as previous but some extra data into a main file" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "CCC")
            clearEvents()
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-1.gz")
            f.write("FFF")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
          }
        }

        "consume all contents if there is demand, and keep watching the file - handle if it is rolled before activation" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize + "FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("D" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectNoEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectNoEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")

          }
        }

        "not consume anything - if it is rolled before activation" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            flowCtx.foreach(activateFlow)
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
          }
        }

        "consume all contents if there is demand, and keep watching the file - rolled before activation - as previous but some extra data into a main file" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            expectNoEvents(ReceivedMessageAtSink, 'Value --> ("A" * testBlockSize))
            expectNoEvents(ReceivedMessageAtSink, 'Value --> ("B" * testBlockSize))
            expectNoEvents(ReceivedMessageAtSink, 'Value --> "CCC")
          }
        }

        "not consume any content - rolled before activation - as previous but multiple rolled files - no main file" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            f.rollGz("current-3.gz")
            flowCtx.foreach(activateFlow)
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
          }
        }

        "not consume any content, and keep watching the file - rolled before activation - as previous but multiple rolled files - no main file - keep watching the file" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            f.rollGz("current-3.gz")
            flowCtx.foreach(activateFlow)
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink)
            }
            f.write("GGG")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "GGG")
          }
        }

        "consume all contents if there is demand - rolled before activation - as previous but multiple rolled files - with main file" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            }
          }
        }

        "consume all contents if there is demand, and keep watching the file - rolled before activation - as previous but multiple rolled files - with main file - keep watching the file" in new EmptyDirWithDemandDeactivatedStartWithLast {
          run { f =>
            f.write("A" * testBlockSize + "B" * testBlockSize + "CCC")
            f.rollGz("current-1.gz")
            f.write("D" * testBlockSize + "E" * testBlockSize)
            f.rollGz("current-2.gz")
            f.write("FFF")
            flowCtx.foreach(activateFlow)
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "FFF")
            waitAndCheck {
              expectNoEvents(ReceivedMessageAtSink, 'Value --> ("E" * testBlockSize))
            }
            clearEvents()
            f.write("GGG")
            expectSomeEvents(1, ReceivedMessageAtSink, 'Value --> "GGG")
          }
        }


      }

      "consistently produce a message" in new WithBasicConfig {
        withDatasourceFlow {

          withNewFile("current.log") { f =>
            f.write("line1\n")

            flowCtx.foreach(activateFlow)

            expectSomeEvents(FileTailerConstants.MessagePublished)

            clearEvents()

            f.write("line2\n")

            expectSomeEvents(FileTailerConstants.MessagePublished)

            clearEvents()

            f.write("line3\n")
            f.rollGz("current-1.gz")
            expectSomeEvents(FileTailerConstants.MessagePublished)

            clearEvents()

            f.write("line4\n")

            expectSomeEvents(FileTailerConstants.MessagePublished)

            clearEvents()

            (1 to 5) foreach { i =>
              f.write(s"line5-${i + 2}\n")
              f.rollGz(s"current-${i + 2}.gz")
              f.write(s"line6-${i + 2}\nline7-${i + 2}\n")

              expectSomeEvents(2, FileTailerConstants.MessagePublished)

              clearEvents()
            }


          }

        }
      }

    }


  }


}