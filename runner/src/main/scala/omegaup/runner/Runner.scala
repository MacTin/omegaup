package omegaup.runner

import java.io._
import java.util.zip._
import javax.servlet._
import javax.servlet.http._
import org.mortbay.jetty._
import org.mortbay.jetty.handler._
import net.liftweb.json._
import scala.collection.{mutable,immutable}
import omegaup._

object Runner extends Object with Log {
	def compile(lang: String, code: List[String], master_lang: Option[String], master_code: Option[List[String]]): CompileOutputMessage = {
		val compileDirectory = new File(Config.get("runner.directory", ".") + "/compile/")
		compileDirectory.mkdirs
		
		var runDirectory = File.createTempFile(System.nanoTime.toString, null, compileDirectory)
		runDirectory.delete
		
		runDirectory = new File(runDirectory.getCanonicalPath.substring(0, runDirectory.getCanonicalPath.length - 4) + "." + lang)
		runDirectory.mkdirs
		
		var fileWriter = new FileWriter(runDirectory.getCanonicalPath + "/Main." + lang)
		fileWriter.write(code(0), 0, code(0).length)
		fileWriter.close
		var inputFiles = mutable.ListBuffer(runDirectory.getCanonicalPath + "/Main." + lang)
		
		for (i <- 1 until code.length) {
			fileWriter = new FileWriter(runDirectory.getCanonicalPath + "/f" + i + "." + lang)
			inputFiles += runDirectory.getCanonicalPath + "/f" + i + "." + lang
			fileWriter.write(code(i), 0, code(i).length)
			fileWriter.close
		}
		
		val sandbox = Config.get("runner.sandbox.path", ".") + "/box"
		val profile = Config.get("runner.sandbox.path", ".") + "/profiles"
		val runtime = Runtime.getRuntime
		
		val process = lang match {
			case "java" =>
				runtime.exec((List(sandbox, "-S", profile + "/javac", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-o", "compile.out", "-r", "compile.err", "--", "/usr/bin/javac") ++ inputFiles).toArray)
			case "c" =>
				runtime.exec((List(sandbox, "-S", profile + "/gcc", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-o", "compile.out", "-r", "compile.err", "--", "/usr/bin/gcc", "-ansi", "-O2", "-lm") ++ inputFiles).toArray)
			case "cpp" =>
				runtime.exec((List(sandbox, "-S", profile + "/gcc", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-o", "compile.out", "-r", "compile.err", "--", "/usr/bin/g++", "-O2", "-lm") ++ inputFiles).toArray)
		}
		
		val status = process.waitFor
		
		if (status == 0) {
			new File(runDirectory.getCanonicalPath + "/compile.meta").delete
			new File(runDirectory.getCanonicalPath + "/compile.out").delete
			new File(runDirectory.getCanonicalPath + "/compile.err").delete
			
			new CompileOutputMessage(token = Some(runDirectory.getName))
		} else {
			val compile_error = FileUtil.read(runDirectory.getCanonicalPath + "/compile.err").replace(runDirectory.getCanonicalPath + "/", "")
			val meta = MetaFile.load(runDirectory.getCanonicalPath + "/compile.meta")
			FileUtil.deleteDirectory(runDirectory)
			
			if (meta.contains("message") && meta("status") != "RE")
				new CompileOutputMessage("compile error", error = Some(meta("message")))
			else
				new CompileOutputMessage("compile error", error = Some(compile_error))
		}
	}
	
	def run(token: String, timeLimit: Float, memoryLimit: Int, outputLimit: Int, input: Option[String], cases: Option[List[CaseData]]) : Unit = {
		val casesDirectory:File = input match {
			case Some(in) => {
				if (in.contains(".") || in.contains("/")) throw new IllegalArgumentException("Invalid input")
				new File (Config.get("runner.directory", ".") + "/input/" + in)
			}
			case None => null
		}
		
		if(casesDirectory != null && !casesDirectory.exists) throw new RuntimeException("missing input")
		if(token.contains("..") || token.contains("/")) throw new IllegalArgumentException("Invalid token")
		
		val runDirectory = new File(Config.get("runner.directory", ".") + "/compile/" + token)
		
		if(!runDirectory.exists) throw new IllegalArgumentException("Invalid token")
		
		val lang = token.substring(token.indexOf(".")+1)
		
		val sandbox = Config.get("runner.sandbox.path", ".") + "/box"
		val profile = Config.get("runner.sandbox.path", ".") + "/profiles"
		val runtime = Runtime.getRuntime
		
		if(casesDirectory != null) {
			casesDirectory.listFiles.filter {_.getName.endsWith(".in")} .foreach { (x) => {
				val caseName = x.getName.substring(0, x.getName.lastIndexOf('.'))
				
				val process = lang match {
					case "java" =>
						runtime.exec((List(sandbox, "-S", profile + "/java", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/" + caseName + ".meta", "-i", x.getCanonicalPath, "-o", caseName + ".out", "-r", caseName + ".err", "-t", timeLimit.toString, "-O", outputLimit.toString, "--", "/usr/bin/java", "-Xmx" + memoryLimit + "k", "Main")).toArray)
					case "c" =>
						runtime.exec((List(sandbox, "-S", profile + "/c", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-i", x.getCanonicalPath, "-o", "compile.out", "-r", "compile.err", "-t", timeLimit.toString, "-O", outputLimit.toString, "-m", memoryLimit.toString, "--", "./a.out")).toArray)
					case "cpp" =>
						runtime.exec((List(sandbox, "-S", profile + "/c", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-i", x.getCanonicalPath, "-o", "compile.out", "-r", "compile.err", "-t", timeLimit.toString, "-O", outputLimit.toString, "-m", memoryLimit.toString, "--", "./a.out")).toArray)
				}
				
				process.waitFor
			}}
		}
		
		cases match {
			case None => {}
			case Some(extra) => {
				extra.foreach { (x: CaseData) => {
					val caseName = x.name
					val casePath = runDirectory.getCanonicalPath + "/" + caseName + ".in"
					
					FileUtil.write(casePath, x.data)
				
					val process = lang match {
						case "java" =>
							runtime.exec((List(sandbox, "-S", profile + "/java", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/" + caseName + ".meta", "-i", casePath, "-o", caseName + ".out", "-r", caseName + ".err", "-t", timeLimit.toString, "-O", outputLimit.toString, "--", "/usr/bin/java", "-Xmx" + memoryLimit + "k", "Main")).toArray)
						case "c" =>
							runtime.exec((List(sandbox, "-S", profile + "/c", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/" + caseName + ".meta", "-i", casePath, "-o", caseName + ".out", "-r", caseName + ".err", "-t", timeLimit.toString, "-O", outputLimit.toString, "-m", memoryLimit.toString, "--", "./a.out")).toArray)
						case "cpp" =>
							runtime.exec((List(sandbox, "-S", profile + "/c", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/" + caseName + ".meta", "-i", casePath, "-o", caseName + ".out", "-r", caseName + ".err", "-t", timeLimit.toString, "-O", outputLimit.toString, "-m", memoryLimit.toString, "--", "./a.out")).toArray)
					}
				
					process.waitFor
				}}
			}
		}
		
		val zipOutput = new ZipOutputStream(new FileOutputStream(runDirectory.getCanonicalPath + "/output.zip"))
		
		runDirectory.listFiles.filter { _.getName.endsWith(".meta") } .foreach { (x) => {
			zipOutput.putNextEntry(new ZipEntry(x.getName))
			
			var inputStream = new FileInputStream(x.getCanonicalPath)
			val buffer = Array.ofDim[Byte](1024)
			var read: Int = 0
	
			while( { read = inputStream.read(buffer); read > 0 } ) {
				zipOutput.write(buffer, 0, read)
			}
			
			inputStream.close
			zipOutput.closeEntry
			
			val meta = MetaFile.load(x.getCanonicalPath)
			
			if(meta("status") == "OK") {
				inputStream = new FileInputStream(x.getCanonicalPath.replace(".meta", ".out"))
				zipOutput.putNextEntry(new ZipEntry(x.getName.replace(".meta", ".out")))
				
				while( { read = inputStream.read(buffer); read > 0 } ) {
					zipOutput.write(buffer, 0, read)
				}
		
				inputStream.close
				zipOutput.closeEntry
			} else if(meta("status") == "RE" && lang == "java") {
				inputStream = new FileInputStream(x.getCanonicalPath.replace(".meta", ".err"))
				zipOutput.putNextEntry(new ZipEntry(x.getName.replace(".meta", ".err")))
				
				while( { read = inputStream.read(buffer); read > 0 } ) {
					zipOutput.write(buffer, 0, read)
				}
		
				inputStream.close
				zipOutput.closeEntry
			}
		}}
		
		zipOutput.close
		
		//FileUtils.deleteDirectory(runDirectory)
	}
	
	def input(request: HttpServletRequest): InputOutputMessage = {
		if(request.getContentType() != "application/zip" || request.getHeader("Content-Disposition") == null) {
			new InputOutputMessage(
				status = "error",
				error = Some("Content-Type must be \"application/zip\", Content-Disposition must be \"attachment\" and a filename must be specified")
			)
		} else {
			val ContentDispositionRegex = "attachment; filename=([a-zA-Z0-9_-][a-zA-Z0-9_.-]*);.*".r
			
			val ContentDispositionRegex(inputName) = request.getHeader("Content-Disposition")
			
			val inputDirectory = new File(Config.get("runner.directory", ".") + "/input/" + inputName)
			inputDirectory.mkdirs()
			
			val input = new ZipInputStream(request.getInputStream)
			var entry: ZipEntry = input.getNextEntry
			val buffer = Array.ofDim[Byte](1024)
			var read: Int = 0
			var reading = true
			
			while(entry != null) {
				val outFile = new File(entry.getName())
				val output = new FileOutputStream(inputDirectory.getCanonicalPath + "/" + outFile.getName)

				while(reading) {
					read = input.read(buffer)
					if (read == -1) reading = false
					else output.write(buffer, 0, read)
				}

				output.close
				input.closeEntry
				entry = input.getNextEntry
			}
			
			input.close
			
			new InputOutputMessage()
		}
	}
	
	def main(args: Array[String]) = {
		// Setting keystore properties
		System.setProperty("javax.net.ssl.keyStore", Config.get("runner.keystore", "omegaup.jks"))
		System.setProperty("javax.net.ssl.trustStore", Config.get("runner.truststore", "omegaup.jks"))
		System.setProperty("javax.net.ssl.keyStorePassword", Config.get("runner.keystore.password", "omegaup"))
		System.setProperty("javax.net.ssl.trustStorePassword", Config.get("runner.truststore.password", "omegaup"))

		// the handler
		val handler = new AbstractHandler() {
			@throws(classOf[IOException])
			@throws(classOf[ServletException])
			def handle(target: String, request: HttpServletRequest, response: HttpServletResponse, dispatch: Int) = {
				implicit val formats = Serialization.formats(NoTypeHints)
				
				response.setContentType("text/json")
				
				Serialization.write(request.getPathInfo() match {
					case "/compile/" => {
						try {
							val req = Serialization.read[CompileInputMessage](request.getReader())
							response.setStatus(HttpServletResponse.SC_OK)
							Runner.compile(req.lang, req.code, req.master_lang, req.master_code)
						} catch {
							case e: Exception => {
								response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
								new CompileOutputMessage(status = "error", error = Some(e.getMessage))
							}
						}
					}
					case "/input/" => {
						try {
							response.setStatus(HttpServletResponse.SC_OK)
							Runner.input(request)
						} catch {
							case e: Exception => {
								response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
								new InputOutputMessage(status = "error", error = Some(e.getMessage))
							}
						}
					}
					case _ => {
						response.setStatus(HttpServletResponse.SC_NOT_FOUND)
						new NullMessage()
					}
				}, response.getWriter())
				
				request.asInstanceOf[Request].setHandled(true)
			}
		};

		// boilerplate code for jetty with https support	
		val server = new Server()
		
		val runnerConnector = new org.mortbay.jetty.security.SslSelectChannelConnector
		runnerConnector.setPort(Config.get("runner.port", 0))
		runnerConnector.setKeystore(Config.get("runner.keystore", "omegaup.jks"))
		runnerConnector.setPassword(Config.get("runner.password", "omegaup"))
		runnerConnector.setKeyPassword(Config.get("runner.keystore.password", "omegaup"))
		runnerConnector.setTruststore(Config.get("runner.truststore", "omegaup.jks"))
		runnerConnector.setTrustPassword(Config.get("runner.truststore.password", "omegaup"))
		runnerConnector.setNeedClientAuth(true)
		
		server.setConnectors(List(runnerConnector).toArray)
		
		server.setHandler(handler)
		server.start()
		
		info("Registering port {}", runnerConnector.getLocalPort())
		
		Https.send[RegisterInputMessage, RegisterOutputMessage](
			Config.get("grader.register.url", "https://localhost:21680/register/"),
			new RegisterInputMessage(runnerConnector.getLocalPort())
		)
		
		java.lang.System.in.read()
		
		Https.send[RegisterInputMessage, RegisterOutputMessage](
			Config.get("grader.deregister.url", "https://localhost:21680/deregister/"),
			new RegisterInputMessage(runnerConnector.getLocalPort())
		)
		
		server.stop()
		server.join()
	}
}

object MetaFile {
	@throws(classOf[IOException])
	def load(path: String): scala.collection.Map[String,String] = {
		val meta = new mutable.ListMap[String,String]
		val fileReader = new BufferedReader(new FileReader(path))
		var line: String = null
	
		while( { line = fileReader.readLine(); line != null} ) {
			val idx = line.indexOf(':')
			
			if(idx > 0) {
				meta += (line.substring(0, idx) -> line.substring(idx+1))
			}
		}
		
		fileReader.close()
		
		meta
	}
}