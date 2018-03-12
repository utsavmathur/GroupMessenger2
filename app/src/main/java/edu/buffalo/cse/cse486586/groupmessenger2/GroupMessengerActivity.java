package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.PriorityQueue;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 * 
 * @author stevko
 *
 */
public class GroupMessengerActivity extends Activity {

    static final String REMOTE_PORT0 = "11108";
    static final String REMOTE_PORT1 = "11112";
    static final String REMOTE_PORT2 = "11116";
    static final String REMOTE_PORT3 = "11120";
    static final String REMOTE_PORT4 = "11124";
    static final String[] ports = {"11108","11112","11116","11120","11124"};
    static final int SERVER_PORT = 10000;
    static int seq_num=0;

    private Uri mUri;

    float proposedseqnum=0.0f;
    float agreedseqnum=0.0f;
    float pid=0.0f;
    float messageid=0.0f;   //very important variable, its format will be x.n, where x is seq num
                            //n is processid. This is used to distinguish between messages from
                            //different processes.
    String failedport="";      //It stores the port number of failed process
    PriorityQueue<Message> holdBackQueue;




    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);

        mUri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
        /*
         * TODO: Use the TextView to display your messages. Though there is no grading component
         * on how you display the messages, if you implement it, it'll make your debugging easier.
         */
        TextView tv = (TextView) findViewById(R.id.textView1);
        tv.setMovementMethod(new ScrollingMovementMethod());

        /*
         * Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));

        /*
         * TODO: You need to register and implement an OnClickListener for the "Send" button.
         * In your implementation you need to get the message from the input box (EditText)
         * and send it to other AVDs.
         */

        final EditText editText = (EditText) findViewById(R.id.editText1);

        holdBackQueue= new PriorityQueue<Message>(30,new MyComparator());

        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        final String myPort = String.valueOf((Integer.parseInt(portStr) * 2));

        pid = ((Integer.parseInt(myPort))-11108)/40.0f;
        Log.i("my","PID of this avd"+pid);
        messageid+=pid;
        //Toast.makeText(getApplicationContext(),""+Float.toString(pid),Toast.LENGTH_LONG).show();

        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.i("my", "Can't create a ServerSocket:"+e.toString());
            return;
        }

        Button bsend=(Button)findViewById(R.id.button4);
        bsend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Toast.makeText(getApplicationContext(),"send button clicked",Toast.LENGTH_LONG).show();
                String msg = editText.getText().toString();
                editText.setText(""); // This is one way to reset the input box.
                TextView localTextView = (TextView) findViewById(R.id.textView1);
                localTextView.append("\t" + msg+"\n"); // This is one way to display a string.

                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, myPort);
                Log.i("my","send button clicked");
            }
        });
    }


    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {


        public void DecideSeqNum(float msgidIncome, float seqnumIncome
                ,String failedportIncome,String msgIncome,boolean flag)
        {
            Log.i("my","DecideSeqNum start");
            try {

                if (flag) {
                    Message temp = new Message(msgidIncome, seqnumIncome, msgIncome);
                    // the seqnumIncome contains proceesid as well.so we need to remove that.
                    float tempseqnumIncome = (float) Math.floor((double) seqnumIncome);
                    agreedseqnum = Math.max(agreedseqnum, tempseqnumIncome);
                    holdBackQueue.remove(temp);
                    temp.deliveryFlag = true;
                    holdBackQueue.add(temp);
                    Log.i("my","agreedseqnum updated and message ready to be delivered.");
                }

                //if first element of the queue is ready to be delivered, then deliver, else check
                // if it needs to be dropped or not, else do nothing.
                while (!holdBackQueue.isEmpty()) {
                    Message temp = holdBackQueue.peek();

                    if (temp.deliveryFlag) {
                        Deliver(holdBackQueue.poll());
                    } else if (temp.deliveryFlag != true && failedportIncome!=null && failedportIncome.isEmpty() != true) {
                        //remove this message from queue if this message is sent by the process which
                        //has failed. we can ignore this message. no need to even deliver it
                        Log.i("my","Failed port is: "+failedportIncome);
                        float temppid = (Integer.parseInt(failedportIncome) - 11108) / 40f;
                        float tempseqnumfrommsgid = (float) Math.floor((double) msgidIncome);
                        float tempmsgid = tempseqnumfrommsgid + temppid;

                        if (Float.compare(temp.messageid, tempmsgid) == 0) {
                            //Deliver(temp);
                            Log.i("my","message removed: msgid:"+temp.messageid+" msg: "+temp.msg+" seq num: "+temp.seqnum);
                            holdBackQueue.remove();
//                            failedport="";
                            failedportIncome = "";
                        }
                        else
                        {
                            Log.i("my","messgae ids do not match");
                            break;
                        }
                    } else {
                        Log.i("my","Nothing condition matched");
                        break;
                    }
                }
            }
            catch (Exception e)
            {
                Log.i("my","DecideSeqNum Excpetion" +e.toString());
            }
            Log.i("my","DecideSeqNum end");
        }

        public String FirstIncomeMsg(float msgidIncome,String msgIncome){
            Log.i("my","FirstIncomeMsg start");
            float seqnumtosend;
            String sendMsg="";
            try {
                proposedseqnum = Math.max(proposedseqnum, agreedseqnum) + 1;
                seqnumtosend = proposedseqnum + pid;
                sendMsg = msgidIncome + ":" + String.valueOf(seqnumtosend);
                Message msgobj = new Message(msgidIncome, seqnumtosend, msgIncome);
                holdBackQueue.add(msgobj);
            }
            catch (Exception e)
            {
                Log.i("my","FirstIncomeMsg Excpetion" +e.toString());
            }
            Log.i("my","FirstIncomeMsg result"+sendMsg);
            Log.i("my","FirstIncomeMsg end");
            return sendMsg;
        }

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            Log.i("my","servertask called");
            ServerSocket serverSocket = sockets[0];
            Socket socket= null;
            DataInputStream in= null;
            try {
                while(true) {
                    socket = serverSocket.accept();
                    Log.i("my","socket accepted");
                    in = new DataInputStream(socket.getInputStream());
                    String line = "";
                    line = in.readUTF();
                    Log.i("my","data received:"+line);

                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());


                    String[] parts=line.split(":");
                    boolean isFinalMsg=Boolean.parseBoolean(parts[0]);
                    float msgidIncome=Float.parseFloat(parts[1]);
                    float seqnumIncome=Float.parseFloat(parts[2]);
                    String failedportIncome=parts[4];
                    boolean IncomeFinalPriorityFlag=Boolean.parseBoolean(parts[3]);
                    String msgIncome=parts[5];
                    if(isFinalMsg)
                    {
                        DecideSeqNum(msgidIncome, seqnumIncome, failedportIncome, msgIncome, false);
                        try {
                            Log.i("my", "sending third reply");
                            out.writeUTF("sending third reply");
                        }catch (SocketTimeoutException e)
                        {
                            Log.i("my","Sendingthirdmsg SocketTimeoutExcpetion" +e.toString());
                        }
                        catch (IOException e)
                        {
                            Log.i("my","SendingThirdmsgIOExcpetion" +e.toString());
                        }
                        catch (Exception e)
                        {
                            Log.i("my","Sendingthirdmsg Excpetion" +e.toString());
                        }
                    }
                    else
                    {
                        if(!IncomeFinalPriorityFlag) {
                            String result=FirstIncomeMsg(msgidIncome, msgIncome);
                            Log.i("my", "sending first reply: " + result);

                            try {
                                out.writeUTF(result);
                            }catch (SocketTimeoutException e)
                            {
                                Log.i("my","FirstIncomeMsg SocketTimeoutExcpetion" +e.toString());
                            }
                            catch (IOException e)
                            {
                                Log.i("my","FirstIncomeMsg IOExcpetion" +e.toString());
                            }
                            catch (Exception e)
                            {
                                Log.i("my","FirstIncomeMsg Excpetion" +e.toString());
                            }
                        }
                        else
                        {
                            DecideSeqNum(msgidIncome,seqnumIncome,failedportIncome,msgIncome,true);

                            try {
                                Log.i("my", "sending second reply");
                                out.writeUTF("sending second reply");
                            }catch (SocketTimeoutException e)
                            {
                                Log.i("my","SendingSecndmsg SocketTimeoutExcpetion" +e.toString());
                            }
                            catch (IOException e)
                            {
                                Log.i("my","SendingSecndmsgIOExcpetion" +e.toString());
                            }
                            catch (Exception e)
                            {
                                Log.i("my","SendingSecndmsg Excpetion" +e.toString());
                            }
                        }
                    }

                    in.close();
                    socket.close();
                }
            }
            catch (SocketTimeoutException e)
            {
                Log.i("my",e.toString());
            }
            catch (IOException e)
            {
                Log.i("my",e.toString());
            }
            catch (Exception e)
            {
                Log.i("my",e.toString());
            }
            return null;
        }

        public void Deliver(Message obj) {
            Log.i("my","Deliver Start");
            Log.i("db","seq:"+seq_num+"|"+obj.msg);
            //code to store the messages in DB  along with sequence number.
            ContentValues cv = new ContentValues();
            cv.put("key", Integer.toString(seq_num));
            cv.put("value", obj.msg);
            getContentResolver().insert(mUri, cv);
            seq_num++;
            publishProgress(obj.msg);
            Log.i("my","Deliver end");
        }

        protected void onProgressUpdate(String...strings) {
            Log.i("my","onProgressUpdate start");
            String strReceived = strings[0].trim();
            TextView remoteTextView = (TextView) findViewById(R.id.textView1);
            remoteTextView.append(strReceived + "\t\n");
            Log.i("my","onprogressupdate end");
            return;
        }
    }


    private class ClientTask extends AsyncTask<String, Void, Void> {


        float incomeseqnum;
        float incomemsgid;
        float maxseqnum;      //store the max seq num received from first reply from all ports
        boolean finalpriority;


        public void SendFirstMsg(String msg){

            Log.i("my","SedFirstMsg starts");
            for(int i=0;i<5;i++)
            {
                try{
                    Log.i("my","first msg, port:"+ports[i]);
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(ports[i]));
                    socket.setSoTimeout(2000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeUTF(msg);


                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    String line = in.readUTF();
                    Log.i("my","first reply: "+ line);
                    String[] parts=line.split(":");
                    incomemsgid=Float.parseFloat(parts[0]);
                    incomeseqnum=Float.parseFloat(parts[1]);
                    if(incomeseqnum>maxseqnum)
                    {
                        maxseqnum=incomeseqnum;
                    }

                    out.close();
                    in.close();
                    socket.close();
                }
                catch (SocketTimeoutException e)
                {
                    if(failedport=="")
                    {
                        failedport=ports[i];
                        Log.i("my","First msg SocketTimeoutException: "+failedport);
                    }
                    Log.i("my",e.toString());
                }
                catch (IOException e)
                {
                    if(failedport=="")
                    {
                        failedport=ports[i];
                        Log.i("my","first msg IOException: "+failedport);
                    }
                    Log.i("my",e.toString());
                }
                catch (Exception e)
                {
                    Log.i("my","first msg Exception"+e.toString());
                }
            }
            Log.i("my","SedFirstMsg ends");
        }

        public void SendSecondMsg(String msg)
        {
            Log.i("My","SendSecondMsg Starts");

            for(int i=0;i<5;i++)
            {
                try{
                    Log.i("my","second msg, port:"+ports[i]);
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(ports[i]));
                    socket.setSoTimeout(2000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeUTF(msg);


                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    String line = in.readUTF();
                    Log.i("my","second reply: "+ line);
                    out.close();
                    in.close();
                    socket.close();
                }
                catch (SocketTimeoutException e)
                {
                    if(failedport=="")
                    {
                        failedport=ports[i];
                        Log.i("my","second msg SocketTimeoutException: "+failedport);
                    }
                    Log.i("my",e.toString());
                }
                catch (IOException e)
                {
                    if(failedport=="")
                    {
                        failedport=ports[i];
                        Log.i("my","second msg IOException: "+failedport);
                    }
                    Log.i("my",e.toString());
                }
                catch (Exception e)
                {
                    Log.i("my","second msg Exception"+e.toString());
                }
            }
            Log.i("My","SendSecondMsg ends");
        }

        public void SendThirdMsg(String msg)
        {
            Log.i("My","SendThirdMsg starts");
            for(int i=0;i<5;i++)
            {
                try{
                    Log.i("my","third msg, port:"+ports[i]);
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(ports[i]));
                    socket.setSoTimeout(2000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeUTF(msg);


                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    String line = in.readUTF();
                    Log.i("my","third reply: "+ line);
                    out.close();
                    in.close();
                    socket.close();
                }
                catch (SocketTimeoutException e)
                {
                    if(failedport=="")
                    {
                        failedport=ports[i];
                        Log.i("my","third msg SocketTimeoutException: "+failedport);
                    }
                    Log.i("my",e.toString());
                }
                catch (IOException e)
                {
                    if(failedport=="")
                    {
                        failedport=ports[i];
                        Log.i("my","third msg IOException: "+failedport);
                    }
                    Log.i("my",e.toString());
                }
                catch (Exception e)
                {
                    Log.i("my","third msg Exception"+e.toString());
                }
            }
            Log.i("My","SendThirdMsg ends");
        }

        @Override
        protected Void doInBackground(String... msgs) {
            String msgToSend = msgs[0];

            messageid=messageid+1.0f;

            incomeseqnum=0.0f;
            incomemsgid=0.0f;
            maxseqnum=0.0f;       //store the max seq num received from first reply from all ports
            finalpriority=false;


            //-------------------1st msg----------------------
            String firstMsg=String.valueOf(false)+":"+String.valueOf(messageid)+":"
                    +String.valueOf(0)+":"+String.valueOf(finalpriority)+":"+failedport+":"+msgToSend;
            Log.i("my","first msg"+firstMsg);

            SendFirstMsg(firstMsg);

            // at the end of this for loop we know the max seq num that can be used for this
            // particular msg. now we need to communicate that sequence number to all ports.
            // So we send the second message
            // Every port will set its agrred sequence number to this number and also set this as
            // the seq num for the message

            //------------------2nd msg-------------------
            finalpriority=true;
            String secondMsg=String.valueOf(false)+":"+String.valueOf(incomemsgid)+":"+
                    String.valueOf(maxseqnum)+":"+String.valueOf(finalpriority)+":"+failedport+":"+msgToSend;

            Log.i("my","second msg"+secondMsg);

            SendSecondMsg(secondMsg);

            //-----------------3rd msg-----------------------
            // We need to send the third message in order to handle failure of app while sending
            //reply of secnd msg. If some port fails while sending second reply we remove corresponding
            //msg from the queue, if it is sent by the process that failed.
            String thirdMsg=String.valueOf(true)+":"+String.valueOf(incomemsgid)+":"+
                    String.valueOf(maxseqnum)+":"+String.valueOf(finalpriority)+":"+failedport+":"+msgToSend;

            Log.i("my","third msg"+thirdMsg);

            SendThirdMsg(thirdMsg);

            return null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }
}


class Message{
    public float messageid;     //it is float because it contains process id after decimal.
    public float seqnum;
    public String msg;
    public boolean deliveryFlag;
    public boolean finalPriorityFlag; //required to identify whether the seq num for the msg is set.

    Message(float mid, float sequencenumber, String message)
    {
        this.messageid=mid;
        this.seqnum=sequencenumber;
        this.msg=message;
        this.deliveryFlag=false;
        this.finalPriorityFlag=false;
    }

    public boolean equals(Object obj)
    {
        Message temp=(Message)obj;
        int flag = Float.compare(this.messageid,temp.messageid);
        return (flag==0);
    }
}
//comparator class is required for implementation of priority queue.
class MyComparator implements java.util.Comparator<Message>
{
    @Override
    public int compare(Message lhs, Message rhs) {
        return (Float.compare(lhs.seqnum,rhs.seqnum));
    }
}
