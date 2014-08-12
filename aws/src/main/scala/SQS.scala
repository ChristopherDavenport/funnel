package oncue.svc.funnel.aws

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{
  AddPermissionRequest,
  CreateQueueRequest,
  GetQueueAttributesRequest,
  Message}
import com.amazonaws.auth.{AWSCredentialsProvider, AWSCredentials}
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.auth.BasicAWSCredentials
import scalaz.concurrent.{Strategy,Task}
import scala.collection.JavaConverters._
import concurrent.duration._
import intelmedia.ws.funnel.Monitoring
import java.util.concurrent.{ExecutorService,ScheduledExecutorService}

object SQS {
  // hard-coded for now as these are so slow moving.
  private val accounts = List(
    "447570741169",
    "460423777025",
    "465404450664",
    "573879536903",
    "596986430194",
    "653211152919",
    "807520270390",
    "825665186404",
    "907213898261",
    "987980579136")

  private val permissions = List(
    "SendMessage",
    "ReceiveMessage",
    "DeleteMessage",
    "ChangeMessageVisibility")

  private val readInterval = 12.seconds

  def client(
    credentials: BasicAWSCredentials,
    awsProxyHost: Option[String] = None,
    awsProxyPort: Option[Int] = None,
    awsProxyProtocol: Option[String] = None,
    region: Region = Region.getRegion(Regions.fromName("us-east-1"))
  ): AmazonSQSClient = { //cfg.require[String]("aws.region"))
    val client = new AmazonSQSClient(
      credentials,
      proxy.configuration(awsProxyHost, awsProxyPort, awsProxyProtocol))
    client.setRegion(region)
    client
  }

  def arnForQueue(url: String)(client: AmazonSQSClient): Task[ARN] = {
    Task {
      val attrs = client.getQueueAttributes(
        new GetQueueAttributesRequest(url, List("QueueArn").asJava)).getAttributes.asScala
      attrs.get("QueueArn")
    }.flatMap {
      case None => Task.fail(new RuntimeException("The specified URL did not have an associated SQS ARN in the specified region."))
      case Some(m) => Task.now(m)
    }
  }

  // http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-long-polling.html
  def create(queueName: String)(client: AmazonSQSClient): Task[ARN] = {
    val req = (new CreateQueueRequest(queueName)).withAttributes(
      Map("MaximumMessageSize"            -> "64000",
          "MessageRetentionPeriod"        -> "1800",
          "ReceiveMessageWaitTimeSeconds" -> (readInterval.toSeconds - 2).toString).asJava)

    for {
      u <- Task(client.createQueue(req).getQueueUrl)
      a <- arnForQueue(u)(client)
      p  = new AddPermissionRequest(u, queueName, accounts.asJava, permissions.asJava)
      _ <- Task(client.addPermission(p))
    } yield a
  }

  import scalaz.stream.Process

  def subscribe(
    queue: ARN,
    wait: Duration = readInterval
  )(client: AmazonSQSClient)(
    implicit pool: ExecutorService = Monitoring.defaultPool,
    schedulingPool: ScheduledExecutorService = Monitoring.schedulingPool
  ): Process[Task, Message] = {
    Process.awakeEvery(wait)(Strategy.Executor(Monitoring.defaultPool), Monitoring.schedulingPool).evalMap { _ =>
      Task {
        val msgs: List[Message] = client.receiveMessage(queue).getMessages.asScala.toList
        msgs.head
      }(Monitoring.defaultPool)
    }
  }

}
