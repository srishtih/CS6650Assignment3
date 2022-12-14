package client2;

import com.opencsv.CSVWriter;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;

/**
 * @author srish
 * WorkerThread that implements Runnable defines the actions to be implemented as clients.
 *
 */
public class WorkerThread implements Runnable {
    private static Object lock = new Object();
    private String payLoad;
    private CountDownLatch latch;
    String serverEnd;
    EventBuffer eventBuffer;
    ResponseBuffer responseBuffer;
    boolean isFirstPhase;
    CloseableHttpClient httpClient;
    int failed = 0;
    FileWriter writer;
    CSVWriter csvWriter;


    /**
     * Constructor for WorkerThread class that takes in all arguments that needs to be passed to the threads
     * @param latch CountDown latch with thread count so the main thread waits until all threads complete execution
     * @param serverEnd Address of the remote server
     * @param eventBuffer  Queue containing events and payloads generated by the producer
     * @param responses Queue containing all received by all client threads for every response sent
     * @param isFirstPhase Indicates if each thread is supposed to execute a fixed number of threads or not
     */
    public WorkerThread(CountDownLatch latch, String serverEnd, EventBuffer eventBuffer, ResponseBuffer responses,
                        boolean isFirstPhase) {
        this.latch = latch;
        this.serverEnd = serverEnd;
        this.eventBuffer = eventBuffer;
        this.responseBuffer = responses;
        this.isFirstPhase = isFirstPhase;
        this.httpClient = HttpClientBuilder.create().build(); //creates client for sending requests
    }

    /**
     * Defines code that is implemented when a thread that implements
     * WorkerThread object is started(i.e.,thread.start())
     * Implements Client part 2 of the assignment
     */
    public void run() {
        int successCount = 0;
        if (isFirstPhase) {
            for (String event = eventBuffer.retrieveEvent(); !event.equals("FIN"); event = eventBuffer.retrieveEvent()) {
                //Each thread implements 1000 requests and terminates as long as consecutive failed attempts is less than 5
                if (successCount < 1000 && failed < 5) {
                    String url = this.serverEnd + event;   // generating url to send POST request to
                    StringEntity entity = new StringEntity(eventBuffer.retrievePayload(), ContentType.APPLICATION_FORM_URLENCODED);
                    HttpPost request = new HttpPost(url);
                    request.setEntity(entity);      //including entity with the POSt request
                    try {
                        Timestamp start = new Timestamp(System.currentTimeMillis());
                        CloseableHttpResponse response = httpClient.execute(request);   //executes POST request
                        Timestamp end = new Timestamp(System.currentTimeMillis());
                        String message = EntityUtils.toString(response.getEntity());    //consuming the response sent
                        int status = response.getStatusLine().getStatusCode();
                        responseBuffer.put(start, end, status);     //add responses to shared queue
                    if (status != 200) {
                        //request failed
                            failed++;
                        } else {
                        //request successful
                            successCount++;
                            failed = 0; //reset failure counter
                        }
                        response.close();   //close response stream to send new request
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                } else {
                    //if requests sent exceeds 1000 or failed attempts exceeds 5, the event read from queue is putEvent back for future use by other threads
                    eventBuffer.putEvent(event);

                }
            }
            latch.countDown();
            //At the end of thread execution, successful requests and failed request count is updated
            Driver.SUCCESSFUL_REQUESTS.getAndAdd(successCount);
            Driver.FAILED_REQUESTS.getAndAdd(failed);
        } else {
            for (String event = eventBuffer.retrieveEvent(); event != "FIN"; event = eventBuffer.retrieveEvent()) {
                //send request until all events generated are sent as post requests
                if (failed < 5) {
                    String url = this.serverEnd + event;       // generating url to send POST request to
                    //creating an entity holding json body
                    StringEntity entity = new StringEntity(eventBuffer.retrievePayload(), ContentType.APPLICATION_FORM_URLENCODED);
                    HttpPost request = new HttpPost(url);
                    request.setEntity(entity);
                    try {
                        Timestamp start = new Timestamp(System.currentTimeMillis());
                        CloseableHttpResponse response = httpClient.execute(request);   //executes POST request
                        Timestamp end = new Timestamp(System.currentTimeMillis());
                        String message = EntityUtils.toString(response.getEntity());    //consuming the response sent
                        int status = response.getStatusLine().getStatusCode();
                        responseBuffer.put(start, end, status);     //add responses to shared queue
                        if (status != 200) {
                            failed++;
                        } else {
                            //Request successful
                            successCount++;
                            failed = 0; //reset failure counter
                        }
                        response.close();   //close response stream to send new request
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                } else {
                    //if requests failed attempts exceeds 5, the event read from queue is putEvent back for future use by other threads
                    eventBuffer.putEvent(event);
                }
            }
            latch.countDown();
            Driver.SUCCESSFUL_REQUESTS.getAndAdd(successCount);
            Driver.FAILED_REQUESTS.getAndAdd(failed);
        }
    }
}