package com.nuvoton.nuisptool_android.ISPTool

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import com.nuvoton.nuisptool_android.Bluetooth.BluetoothLeCmdManager
import com.nuvoton.nuisptool_android.Util.Log
import com.nuvoton.nuisptool_android.Util.HEXTool
import com.nuvoton.nuisptool_android.WiFi.SocketCmdManager
import kotlin.concurrent.thread
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

data class ConnectResult(
    val buffer: ByteArray?,
    val isChecksum: Boolean,
    val isTimeout: Boolean
)

data class CommandResult(
    val buffer: ByteArray?,
    val isChecksum: Boolean
)

enum class NulinkInterfaceType constructor(val value: Byte) {

    USB    (0x00),
//  HID    (0x01),
    UART   (0x00),
    SPI    (0x03),
    I2C    (0x04),
    RS485  (0x05),
    CAN    (0x06),
    WiFi   (0x07),
    BLE    (0x08)
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
object ISPManager {
    /**
     * Config 設定值
     */
    private var read_endpoint_index = 0
    private var write_endpoint_index = 1
    private var connect_interface_index = 0
    private var byteSize = 64
    private val forceClaim = true
    private val timeOut = 100
    private val isSearchLoop = false

    public var packetNumber: UInt = (0x00000005).toUInt()
    public var interfaceType : NulinkInterfaceType = NulinkInterfaceType.USB
    
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null

    private var _readListener: ((ByteArray) -> Unit)? = null
    private var _byteArrayResultListener: ((ByteArray) -> Unit)? = null

    fun sendCMD_CAN_GET_DEVICE( callback: ((ByteArray?) -> Unit)) {

        val cmd = ISPCanCommands.CMD_CAN_GET_DEVICE
        val sendBuffer = ISPCommandTool.toCanGetDeviceCMD()
        thread {
            this.executeWriteRead(sendBuffer, 100, callback = { readBuffer, isTimeout ->
                callback.invoke(readBuffer)
            })
        }

    }

    private fun sendCMD_CAN_READ_CONFIG( callback: ((ByteArray?) -> Unit)){

        var configbyteArray = byteArrayOf(0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)

        val sendBuffer0 = ISPCommandTool.toCanReadConfigCMD(0)
        this.write( sendBuffer0)
        val readBuffer0 = this.read()

        Thread.sleep(100)

        val sendBuffer1 = ISPCommandTool.toCanReadConfigCMD(1)
        this.write( sendBuffer1)
        val readBuffer1 = this.read()

        Thread.sleep(100)

        val sendBuffer2 = ISPCommandTool.toCanReadConfigCMD(2)
        this.write( sendBuffer2)
        val readBuffer2 = this.read()

        Thread.sleep(100)

        val sendBuffer3 = ISPCommandTool.toCanReadConfigCMD(3)
        this.write( sendBuffer3)
        val readBuffer3 = this.read()

        configbyteArray = configbyteArray + readBuffer0!!.copyOfRange(4,8) + readBuffer1!!.copyOfRange(4,8) + readBuffer2!!.copyOfRange(4,8) + readBuffer3!!.copyOfRange(4,8)

        callback.invoke(configbyteArray)
    }

    private fun sendCMD_CAN_UPDATE_CONFIG(config0: UInt,config1: UInt,config2: UInt,config3: UInt, callback: ((ByteArray?) -> Unit)) {

        var configbyteArray = byteArrayOf(0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)

        val sendBuffer0 = ISPCommandTool.toCanUpdateConfigCMD(0,config0)
        this.write( sendBuffer0)
        val readBuffer0 = this.read()

        val sendBuffer1 = ISPCommandTool.toCanUpdateConfigCMD(1,config1)
        this.write( sendBuffer1)
        val readBuffer1 = this.read()

        val sendBuffer2 = ISPCommandTool.toCanUpdateConfigCMD(2,config2)
        this.write( sendBuffer2)
        val readBuffer2 = this.read()

        val sendBuffer3 = ISPCommandTool.toCanUpdateConfigCMD(3,config3)
        this.write( sendBuffer3)
        val readBuffer3 = this.read()

        configbyteArray = configbyteArray + readBuffer0!!.copyOfRange(4,8) + readBuffer1!!.copyOfRange(4,8) + readBuffer2!!.copyOfRange(4,8) + readBuffer3!!.copyOfRange(4,8)

        callback.invoke(configbyteArray)
    }

    fun sendCMD_CAN_UPDATE_BIN( sendByteArray:ByteArray,startAddress:UInt, callback: ((ByteArray?, Int) -> Unit)) {

        var remainDataList: List<ByteArray> = listOf()
        var dataArray = byteArrayOf()
        var index = 0

        for ( sendByte in sendByteArray){
            dataArray = dataArray + sendByte
            index = index + 1
            if(index == 4){
                remainDataList = remainDataList + dataArray.clone()
                dataArray = byteArrayOf()
                index = 0
            }
        }
        if(dataArray.isNotEmpty()){
            //還有剩
            for(i in dataArray.size+1..4){
                dataArray = dataArray + 0x00 //後面補 0x00
            }
            if(dataArray.size == 4){
                remainDataList = remainDataList + dataArray.clone()
            }
        }

        Log.i("ISPManager", "CAN_UPDATE  size:"+sendByteArray.size+"  allPackNum:"+dataArray.size)

        var address = HEXTool.UIntTo4Bytes(startAddress)

        for (i in 0..remainDataList.size-1){

            var sendBuffer = ISPCommandTool.toUpdataBin_CAN_CMD(ISPCanCommands.CMD_CAN_UPDATE_APROM , address , sendByteArray.size ,remainDataList[i] )
            this.write( sendBuffer)
            var readBuffer = this.read()
            var isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)

            address = HEXTool.UIntTo4Bytes(HEXTool.bytesToUInt(address) + 4u)

            if(isChecksum != true){
                callback.invoke(readBuffer, -1)
                return
            }
            callback.invoke(readBuffer, (i.toDouble() / (remainDataList.size -1) * 100).toInt())
        }
    }

    fun sendCMD_UPDATE_BIN(cmd: ISPCommands ,sendByteArray:ByteArray,startAddress:UInt, callback: ((ByteArray?, Int) -> Unit)) {

        //如果是BLE
        if(ISPManager.interfaceType == NulinkInterfaceType.BLE){
            BluetoothLeCmdManager.sendCMD_UPDATE_BIN(cmd,sendByteArray,startAddress, callback = { readBuffer , P ->
                callback.invoke(readBuffer, P)
            })
            return
        }

        //如果是WiFi
        if(ISPManager.interfaceType == NulinkInterfaceType.WiFi){
            SocketCmdManager.sendCMD_UPDATE_BIN(cmd,sendByteArray,startAddress, callback = { readBuffer, P ->
                callback.invoke(readBuffer, P)
            })
            return
        }

        //如果是UART
        if(ISPManager.interfaceType == NulinkInterfaceType.UART){
            SerialManager.sendCMD_UPDATE_BIN(cmd,sendByteArray,startAddress, callback = { readBuffer, P ->
                callback.invoke(readBuffer, P)
            })
            return
        }

        if(cmd != ISPCommands.CMD_UPDATE_APROM && cmd != ISPCommands.CMD_UPDATE_DATAFLASH){
            return
        }

        //如果是CAN
        if(ISPManager.interfaceType == NulinkInterfaceType.CAN){
            this.sendCMD_CAN_UPDATE_BIN(sendByteArray,startAddress,callback)
            return
        }

       var firstData = byteArrayOf()//第一個cmd 為 48 byte
        for (i in 0..47){
            firstData = firstData + sendByteArray[i]
        }
        var remainDataList: List<ByteArray> = listOf()
        val remainData = sendByteArray.copyOfRange(48,sendByteArray.lastIndex+1)//第一個cmd 為 56 byte
//        val remainData = ByteArray(sendByteArray.size - 48)//第一個cmd 為 56 byte
        var index = 0
        var dataArray = byteArrayOf()
        for (byte in remainData){
            dataArray = dataArray + byte
            index = index + 1

            if(index == 56){
                index = 0
                remainDataList = remainDataList + dataArray.clone()
                dataArray = byteArrayOf()
            }
        }
        if(dataArray.isNotEmpty()){
            //還有剩
                for(i in dataArray.size+1..56){
                    dataArray = dataArray + 0x00
                }

            if(dataArray.size == 56){
                remainDataList = remainDataList + dataArray.clone()
            }

        }
        Log.i("ISPManager", "CMD_UPDATE   CMD:"+cmd.toString()+"  size:"+sendByteArray.size+"  allPackNum:"+dataArray.size+1)
        var sendBuffer = ISPCommandTool.toUpdataBin_CMD(cmd, packetNumber , startAddress , sendByteArray.size , firstData , true)
        this.write( sendBuffer)
        var readBuffer = this.read()
        var isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)

        callback.invoke(readBuffer, 0) //5% 起跳

        if(isChecksum != true){
            callback.invoke(readBuffer, -1)
            return
        }

        for (i in 0..remainDataList.size-1){
            sendBuffer = ISPCommandTool.toUpdataBin_CMD(cmd, packetNumber , startAddress , sendByteArray.size , remainDataList[i] , false)
            this.write( sendBuffer)
            readBuffer = this.read()
            isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)
            if(isChecksum != true){
                callback.invoke(readBuffer, -1)
                return
            }

            callback.invoke(readBuffer, (i.toDouble() / remainDataList.size * 100).toInt())
        }
        callback.invoke(readBuffer, 100)
    }


    fun sendCMD_ERASE_ALL( callback: ((ByteArray?, Boolean) -> Unit)) {

        //如果是BLE
        if(ISPManager.interfaceType == NulinkInterfaceType.BLE){
            BluetoothLeCmdManager.sendCMD_ERASE_ALL { readBuffer, isChecksum ->
                callback.invoke(readBuffer, isChecksum)
            }
            return
        }

        //如果是WiFi
        if(ISPManager.interfaceType == NulinkInterfaceType.WiFi){
            SocketCmdManager.sendCMD_ERASE_ALL { readBuffer, isChecksum ->
                callback.invoke(readBuffer, isChecksum)
            }
            return
        }

        //如果是UART
        if(ISPManager.interfaceType == NulinkInterfaceType.UART){
            SerialManager.sendCMD_ERASE_ALL { readBuffer, isChecksum ->
                callback.invoke(readBuffer, isChecksum)
            }
            return
        }

        val cmd = ISPCommands.CMD_ERASE_ALL
        val sendBuffer = ISPCommandTool.toCMD(cmd, packetNumber)

        this.write( sendBuffer)
        val readBuffer = this.read()
        var isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)

        callback.invoke(readBuffer, isChecksum)
    }

    fun sendCMD_READ_CONFIG( callback: ((ByteArray?) -> Unit)) {

        //如果是BLE
        if(ISPManager.interfaceType == NulinkInterfaceType.BLE){
            BluetoothLeCmdManager.sendCMD_READ_CONFIG {
                callback.invoke(it)
            }
            return
        }

        //如果是WiFi
        if(ISPManager.interfaceType == NulinkInterfaceType.WiFi){
            SocketCmdManager.sendCMD_READ_CONFIG {
                callback.invoke(it)
            }
            return
        }

        //如果是UART
        if(ISPManager.interfaceType == NulinkInterfaceType.UART){
            SerialManager.sendCMD_READ_CONFIG {
                callback.invoke(it)
            }
            return
        }

        //如果是CAN
        if(ISPManager.interfaceType == NulinkInterfaceType.CAN){
            this.sendCMD_CAN_READ_CONFIG(callback = {
                callback.invoke(it)
            })
            return
        }        
        val cmd = ISPCommands.CMD_READ_CONFIG       
        val sendBuffer = ISPCommandTool.toCMD(cmd, packetNumber)        
        Log.i("ISPManager", "sendCMD cmd=$cmd packetNumber=$packetNumber")        
        this.write(sendBuffer)        
        val expectedPackNo = packetNumber + 1.toUInt()        
        val readBuffer = waitForExpectedPacket(expectedPackNo)        
        if (readBuffer == null) {        
            Log.i("ISPManager", "READ_CONFIG readBuffer == null")        
            callback.invoke(null)        
            return
        }        
        var isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)        
        callback.invoke(readBuffer)
    }
    
    suspend fun suspendCMD_READ_CONFIG(): ByteArray? =
        suspendCancellableCoroutine { cont ->
    
            sendCMD_READ_CONFIG { buffer ->
    
                if (cont.isActive) {
                    cont.resume(buffer)
                }
            }
        }

    fun sendCMD_GET_FWVER(callback: ((ByteArray?, Boolean) -> Unit)) {

        //如果是BLE
        if(ISPManager.interfaceType == NulinkInterfaceType.BLE){
            BluetoothLeCmdManager.sendCMD_GET_FWVER { readBuffer, isChecksum ->
                callback.invoke(readBuffer, isChecksum)
            }
            return
        }

        //如果是WiFi
        if(ISPManager.interfaceType == NulinkInterfaceType.WiFi){
            SocketCmdManager.sendCMD_GET_FWVER { readBuffer, isChecksum ->
                callback.invoke(readBuffer, isChecksum)
            }
            return
        }

        //如果是UART
        if(ISPManager.interfaceType == NulinkInterfaceType.UART){
            SerialManager.sendCMD_GET_FWVER { readBuffer, isChecksum ->
                callback.invoke(readBuffer, isChecksum)
            }
            return
        }
        
        val cmd = ISPCommands.CMD_GET_FWVER
        val sendBuffer = ISPCommandTool.toCMD(cmd, packetNumber)
        Log.i("ISPManager", "sendCMD cmd=${cmd} packetNumber=$packetNumber")
        this.write( sendBuffer)
        val expectedPackNo = packetNumber + 1.toUInt()        
        val readBuffer = waitForExpectedPacket(expectedPackNo)
        var isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)
        callback.invoke(readBuffer, isChecksum)
    }
    
    suspend fun suspendCMD_GET_FWVER(): CommandResult =
        suspendCancellableCoroutine { cont ->
    
            sendCMD_GET_FWVER { buffer, isChecksum ->
    
                if (cont.isActive) {
                    cont.resume(
                        CommandResult(
                            buffer,
                            isChecksum
                        )
                    )
                }
            }
        }

    fun sendCMD_RUN_APROM( callback: ((Boolean) -> Unit)) {

        //如果是BLE
        if(ISPManager.interfaceType == NulinkInterfaceType.BLE){
            BluetoothLeCmdManager.sendCMD_RUN_APROM {
                callback.invoke(it)
            }
            return
        }

        //如果是WiFi
        if(ISPManager.interfaceType == NulinkInterfaceType.WiFi){
            SocketCmdManager.sendCMD_RUN_APROM {
                callback.invoke(it)
            }
            return
        }

        //如果是UART
        if(ISPManager.interfaceType == NulinkInterfaceType.UART){
            SerialManager.sendCMD_RUN_APROM {
                callback.invoke(it)
            }
            return
        }

        if(ISPManager.interfaceType == NulinkInterfaceType.CAN){
            val sendBuffer = ISPCommandTool.toCanRunAPROM_CMD()
            this.write( sendBuffer)
            callback.invoke(true)
            return
        }

        val cmd = ISPCommands.CMD_RUN_APROM
        val sendBuffer = ISPCommandTool.toCMD(cmd, packetNumber)
        this.write( sendBuffer)

        callback.invoke(true)
    }

    fun sendCMD_UPDATE_CONFIG(config0: UInt,config1: UInt,config2: UInt,config3: UInt, callback: ((ByteArray?) -> Unit)) {

        //如果是BLE
        if(ISPManager.interfaceType == NulinkInterfaceType.BLE){
            BluetoothLeCmdManager.sendCMD_UPDATE_CONFIG(config0,config1,config2,config3, callback = {
                    callback.invoke(it)
            })
            return
        }

        //如果是WiFi
        if(ISPManager.interfaceType == NulinkInterfaceType.WiFi){
            SocketCmdManager.sendCMD_UPDATE_CONFIG(config0,config1,config2,config3, callback = {
                callback.invoke(it)
            })
            return
        }

        //如果是UART
        if(ISPManager.interfaceType == NulinkInterfaceType.UART){
            SerialManager.sendCMD_UPDATE_CONFIG(config0,config1,config2,config3, callback = {
                callback.invoke(it)
            })
            return
        }

        //如果是CAN
        if(ISPManager.interfaceType == NulinkInterfaceType.CAN){
            this.sendCMD_CAN_UPDATE_CONFIG(config0,config1,config2,config3, callback = {
                callback.invoke(it)
            })
            return
        }


        sendCMD_ERASE_ALL() { readArray, isChackSum ->

            if (isChackSum != true) return@sendCMD_ERASE_ALL

            //config＿1  先寫死
            val cmd = ISPCommands.CMD_UPDATE_CONFIG
            val sendBuffer = ISPCommandTool.toUpdataCongigeCMD(config0, config1, config2,config3, packetNumber)
            this.write( sendBuffer)

            val readBuffer = this.read()
            var isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)

            callback.invoke(readBuffer)
        }

    }

    fun sendCMD_SYNC_PACKNO(
        callback: ((ByteArray?, Boolean) -> Unit)
    ) {
    
        val cmd = ISPCommands.CMD_SYNC_PACKNO    
        val sendBuffer = ISPCommandTool.toCMD(cmd, packetNumber)    

        // SYNC requires duplicated packet number in bytes 8-11.
        val packNoBytes = HEXTool.UIntTo4Bytes(packetNumber)    
        System.arraycopy(packNoBytes, 0, sendBuffer, 8, 4)    
        Log.i(
            "ISPManager",
            "sendCMD cmd=$cmd " +
            "packetNumber=$packetNumber " +
            "syncPackNo=$packetNumber"
        )    
        this.write(sendBuffer)    
        val expectedPackNo = packetNumber + 1.toUInt()    
        val readBuffer = waitForExpectedPacket(expectedPackNo)    
        val isChecksum = this.isChecksum_PackNo(
            sendBuffer,
            readBuffer
        )    
        callback.invoke(readBuffer, isChecksum)
    }
    
    suspend fun suspendCMD_SYNC_PACKNO(): CommandResult =
        suspendCancellableCoroutine { cont ->
    
            sendCMD_SYNC_PACKNO { buffer, isChecksum ->
    
                if (cont.isActive) {
                    cont.resume(
                        CommandResult(
                            buffer,
                            isChecksum
                        )
                    )
                }
            }
        }

    fun sendCMD_CONNECT(callback: ((ByteArray?, Boolean, Boolean) -> Unit)) {
 
        //如果是UART
        if (ISPManager.interfaceType == NulinkInterfaceType.UART) {
            SerialManager.sendCMD_CONNECT { bytes, b, isTimeout ->
                callback.invoke(bytes, b, isTimeout)
            }
            return
        }
        executeConnect { readBuffer, isTimeout ->
            val sendBuffer =
                ISPCommandTool.toCMD(
                    ISPCommands.CMD_CONNECT,
                    1.toUInt()
                )
            val isChecksum =
                this.isChecksum_PackNo(
                    sendBuffer,
                    readBuffer
                )
            callback.invoke(
                readBuffer,
                isChecksum,
                isTimeout
            )
        }
    }
  
    @SuppressLint("NewApi")
    private fun executeConnect(callback: (ByteArray?, Boolean) -> Unit) {    
        val usbDevice = OTGManager.get_USBDevice()
        if (interfaceType != NulinkInterfaceType.USB) {
            for (i in 0 until usbDevice.interfaceCount) {
                if (
                    usbDevice.getInterface(i).name != null &&
                    usbDevice.getInterface(i).name!!.contains("ISP")
                ) {
                    connect_interface_index = i
                }
            }
        }
        else {
            connect_interface_index = 0
        }
    
        if (
            usbConnection == null ||
            readEndpoint == null ||
            writeEndpoint == null
        ) {        
            Log.i("ISPManager", "executeConnect: USB session not open")        
            callback.invoke(null, true)        
            return
        }
        
        val connection = usbConnection!!
        val writePoint = writeEndpoint!!
        val readPoint = readEndpoint!!        
        val readBuffer = ByteArray(64)    
        var index = 0
        
        // Send CONNECT packets until a non-zero response is received,
        // indicating the device has transitioned into ISP mode.  
        while (index < 20) {    
            packetNumber = 1.toUInt()    
            val sendBuffer =
                ISPCommandTool.toCMD(
                    ISPCommands.CMD_CONNECT,
                    packetNumber
                )    
            Log.i(
                "ISPManager",
                "sendCMD cmd=CMD_CONNECT packetNumber=$packetNumber"
            )    
            var sendBufferString = HEXTool.toHexString(sendBuffer)    
            var display = HEXTool.toDisPlayString(sendBufferString)    
            val isWrite =
                connection.bulkTransfer(
                    writePoint,
                    sendBuffer,
                    sendBuffer.size,
                    0
                )    
            Log.i("ISPManager", "isWrite=$isWrite    ,sendBuffer:  $display")    
            readBuffer.fill(0)    
            val isRead =
                connection.bulkTransfer(
                    readPoint,
                    readBuffer,
                    readBuffer.size,
                    100
                )    
            val readBufferString = HEXTool.toHexString(readBuffer)    
            display = HEXTool.toDisPlayString(readBufferString)
            Log.i("ISPManager", "isRead=$isRead    ,readBuffer:  $display")    
            if (isRead <= 0) {    
                Thread.sleep(200)    
                index++    
                Log.i("ISPManager", "index=$index")
                continue
            }    
            val allZero = readBuffer.all { it == 0.toByte() }    
            if (!allZero) {    
                Log.i("ISPManager", "Holfuy entered ISP mode")    
                callback.invoke(readBuffer, false)    
                return
            }    
            Thread.sleep(200)    
            index++    
            Log.i("ISPManager", "index=$index")
        }
        closeUsbSession()
        callback.invoke(null, true)
    }
    
    suspend fun suspendCMD_CONNECT(): ConnectResult =
        suspendCancellableCoroutine { cont ->
    
            sendCMD_CONNECT { buffer, isChecksum, isTimeout ->
    
                if (cont.isActive) {
                    cont.resume(
                        ConnectResult(
                            buffer,
                            isChecksum,
                            isTimeout
                        )
                    )
                }
            }
        }

    @SuppressLint("NewApi")
    fun openUsbSession(): Boolean {  
        if (
            usbConnection != null &&
            readEndpoint != null &&
            writeEndpoint != null
        ) {        
            Log.i("ISPManager", "openUsbSession already open")        
            return true
        }  
        val usbDevice = OTGManager.get_USBDevice()    
        if (interfaceType != NulinkInterfaceType.USB) {    
            for (i in 0 until usbDevice.interfaceCount) {    
                if (
                    usbDevice.getInterface(i).name != null &&
                    usbDevice.getInterface(i).name!!.contains("ISP")
                ) {
                    connect_interface_index = i
                }
            }
        }
        else {
            connect_interface_index = 0
        }    
        usbInterface = usbDevice.getInterface(connect_interface_index)    
        readEndpoint = usbInterface!!.getEndpoint(read_endpoint_index)    
        writeEndpoint = usbInterface!!.getEndpoint(write_endpoint_index)    
        usbConnection = OTGManager.USBManager.openDevice(usbDevice)    
        if (usbConnection == null) {    
            Log.i("ISPManager", "openUsbSession failed: usbConnection == null")    
            return false
        }    
        val claimed = usbConnection!!.claimInterface(usbInterface, forceClaim)    
        Log.i("ISPManager", "openUsbSession claimInterface=$claimed")    
        return claimed
    }
    
    fun closeUsbSession() {    
        try {    
            usbConnection?.releaseInterface(usbInterface)    
        } catch (_: Exception) {
        }
    
        try {    
            usbConnection?.close()    
        } catch (_: Exception) {
        }
    
        usbConnection = null
        usbInterface = null
        readEndpoint = null
        writeEndpoint = null    
        Log.i("ISPManager", "USB session closed")
    }

    fun sendCMD_GET_DEVICEID( callback: ((ByteArray?, Boolean) -> Unit)) {

        //如果是BLE
        if(ISPManager.interfaceType == NulinkInterfaceType.BLE){
            BluetoothLeCmdManager.sendCMD_GET_DEVICEID { readBuffer, isChecksum ->
                callback.invoke(readBuffer,isChecksum)
            }
            return
        }

        //如果是WiFi
        if(ISPManager.interfaceType == NulinkInterfaceType.WiFi){
            SocketCmdManager.sendCMD_GET_DEVICEID { readBuffer, isChecksum ->
                callback.invoke(readBuffer,isChecksum)
            }
            return
        }

        //如果是UART
         if(ISPManager.interfaceType == NulinkInterfaceType.UART){
            SerialManager.sendCMD_GET_DEVICEID { readBuffer, isChecksum ->
                callback.invoke(readBuffer,isChecksum)
            }
            return
        }
        val cmd = ISPCommands.CMD_GET_DEVICEID
        val sendBuffer = ISPCommandTool.toCMD(cmd, packetNumber)
        Log.i("ISPManager", "sendCMD cmd=${cmd} packetNumber=$packetNumber")  
        this.write( sendBuffer)
        val expectedPackNo = packetNumber + 1.toUInt()
        val readBuffer = waitForExpectedPacket(expectedPackNo)
        var isChecksum = this.isChecksum_PackNo(sendBuffer, readBuffer)
        callback.invoke(readBuffer,isChecksum)
    }
    
    suspend fun suspendCMD_GET_DEVICEID(): CommandResult =
        suspendCancellableCoroutine { cont ->
    
            sendCMD_GET_DEVICEID { buffer, isChecksum ->
    
                if (cont.isActive) {
                    cont.resume(
                        CommandResult(
                            buffer,
                            isChecksum
                        )
                    )
                }
            }
        }

    public fun isChecksum_PackNo(sendBuffer: ByteArray, readBuffer: ByteArray?): Boolean {

        if (readBuffer == null) {
            Log.i("isChecksum_PackNo", "readBuffer == null")
            return false
        }

        //如果是CAN 無條件回true CAN沒有Checksum
        if(ISPManager.interfaceType == NulinkInterfaceType.CAN){
            return true
        }

        // checksum
        val checksum = ISPCommandTool.toChecksumBySendBuffer(sendBuffer)
        val resultChecksum = ISPCommandTool.toChecksumByReadBuffer(readBuffer)
        Log.i("isChecksum_PackNo", "computedChecksum=$checksum resultChecksum=$resultChecksum")
        val sendDisplay = HEXTool.toDisPlayString(HEXTool.toHexString(sendBuffer))
        Log.i("isChecksum_PackNo", "checksumSendBuffer: $sendDisplay")
        if (checksum != resultChecksum) {
            Log.i(
                "isChecksum_PackNo",
                "checksum $checksum != resultChecksum $resultChecksum"
            )
            return false
        }
        
        // packet number
        val packNo = packetNumber + (0x00000001).toUInt()
        val resultPackNo = ISPCommandTool.toPackNo(readBuffer)
        
        if (packNo != resultPackNo) {
            Log.i(
                "isChecksum_PackNo",
                "packNo $packNo != resultPackNo $resultPackNo"
            )
            return false
        }
        packetNumber = packNo + (0x00000001).toUInt()
        Log.i(
            "isChecksum_PackNo",
            "packNo $packNo == resultPackNo $resultPackNo ,checksum $checksum == resultChecksum $resultChecksum"
        )
        return true
    }

    @SuppressLint("NewApi")
    // timeoutIndex currently unused. Retained to avoid broad caller churn during transport refactor.
    private fun executeWriteRead(cmdArray: ByteArray, timeoutIndex: Int, 
      callback: (ByteArray?, isTimeout: Boolean) -> Unit) {
    
        //如果是Uart
        if (ISPManager.interfaceType == NulinkInterfaceType.UART) {
            SerialManager.executeWriteRead(
                cmdArray,
                timeoutIndex,
                callback
            )
            return
        }
    
        val usbDevice = OTGManager.get_USBDevice()
        if (interfaceType != NulinkInterfaceType.USB) {
            for (i in 0 until usbDevice.interfaceCount) {
                if (
                    usbDevice.getInterface(i).name != null &&
                    usbDevice.getInterface(i).name!!.contains("ISP")
                ) {
                    connect_interface_index = i
                }
            }
    
        } else {
            connect_interface_index = 0
        }
    
        val intf = usbDevice.getInterface(connect_interface_index)
        val writePoint = intf.getEndpoint(write_endpoint_index)
        val readPoint = intf.getEndpoint(read_endpoint_index)
        val connection = OTGManager.USBManager.openDevice(usbDevice)
        connection.claimInterface(intf, forceClaim)
        val readBuffer = ByteArray(64)
        val sendBuffer = cmdArray.clone()
        sendBuffer[1] = interfaceType.value
        val sentPacketNumber = ISPCommandTool.toPackNo(sendBuffer)
        val expectedPackNo = sentPacketNumber + 1.toUInt()
        var index = 0
        while (index < 20) {
            var sendBufferString = HEXTool.toHexString(sendBuffer)
            var display = HEXTool.toDisPlayString(sendBufferString)
            val isWrite = connection.bulkTransfer(writePoint, sendBuffer, sendBuffer.size, 0)
            Log.i("ISPManager", "isWrite=$isWrite    ,sendBuffer:  $display")
            readBuffer.fill(0)
            val isRead = connection.bulkTransfer(readPoint, readBuffer, readBuffer.size, 100 )
            val readBufferString = HEXTool.toHexString(readBuffer)
            display = HEXTool.toDisPlayString(readBufferString)
            Log.i("ISPManager", "isRead=$isRead    ,readBuffer:  $display")
            Log.i("ISPManager", "cmd=${cmdArray[0].toUByte()} packetNumber=$packetNumber")
            if (isRead <= 0) {
                Thread.sleep(200)
                index++
                Log.i("ISPManager", "index=$index")
                continue
            }
            val resultPackNo = ISPCommandTool.toPackNo(readBuffer)
            if (resultPackNo != expectedPackNo) {
                Log.i("ISPManager", "Ignoring stale packet $resultPackNo, expected $expectedPackNo")
                Thread.sleep(200)
                index++
                Log.i("ISPManager", "index=$index")
                continue
            }
            callback.invoke(readBuffer, false)
            return
        }
        callback.invoke(null, true)
    }
    
    @SuppressLint("NewApi")
    private fun read(): ByteArray? {    
        val connection = usbConnection
        val endpoint = readEndpoint    
        if (connection == null || endpoint == null) {    
            Log.i("ISPManager", "read failed: USB session not open")    
            return null
        }    
        val readBuffer = ByteArray(64)    
        val i =
            connection.bulkTransfer(
                endpoint,
                readBuffer,
                readBuffer.size,
                100
            )    
        val readBufferString = HEXTool.toHexString(readBuffer)    
        val display = HEXTool.toDisPlayString(readBufferString)    
        Log.i("ISPManager", "i=$i    ,readBuffer:  $display")    
        if (i <= 0) {
            return null
        }    
        return readBuffer
    }
    
    // Since the device uses USB HID semantics, each response to a write
    // command is left available for an infinite number of read commands.
    // This function reads and discards packets that do not contain the expected
    // packet number, returning the expected packet when it is made available by the device.
    private fun waitForExpectedPacket(
        expectedPackNo: UInt,
        maxAttempts: Int = 20
    ): ByteArray? {    
        var index = 0    
        while (index < maxAttempts) {    
            val readBuffer = this.read()    
            if (readBuffer == null) {    
                Log.i(
                    "ISPManager",
                    "waitForExpectedPacket readBuffer == null"
                )    
                index++    
                continue
            }    
            val resultPackNo = ISPCommandTool.toPackNo(readBuffer)    
            val resultChecksum = ISPCommandTool.toChecksumByReadBuffer(readBuffer)    
            Log.i(
                "ISPManager",
                "waitForExpectedPacket " +
                "resultPackNo=$resultPackNo " +
                "expectedPackNo=$expectedPackNo " +
                "checksum=$resultChecksum"
            )    
            if (resultPackNo != expectedPackNo) {    
                val readBufferString = HEXTool.toHexString(readBuffer)    
                val display = HEXTool.toDisPlayString(readBufferString)    
                Log.i("ISPManager", "Ignoring unexpected packet: $display")    
                index++    
                continue
            }    
            return readBuffer
        }    
        Log.i(
            "ISPManager", "waitForExpectedPacket timeout waiting for packNo=$expectedPackNo")    
        return null
    }
    
    @SuppressLint("NewApi")
    private fun write(cmdArray: ByteArray) {    
        val connection = usbConnection
        val endpoint = writeEndpoint    
        if (connection == null || endpoint == null) {    
            Log.i(
                "ISPManager", "write failed: USB session not open")    
            return
        }    
        cmdArray[1] = interfaceType.value    
        val sendBuffer = cmdArray    
        val sendBufferString = HEXTool.toHexString(sendBuffer)    
        val display = HEXTool.toDisPlayString(sendBufferString)    
        val i =
            connection.bulkTransfer(
                endpoint,
                sendBuffer,
                sendBuffer.size,
                timeOut
            )    
        Log.i("ISPManager", "i=$i    ,writeBuffer: $display")
    }
}

