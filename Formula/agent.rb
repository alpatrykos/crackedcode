class Agent < Formula
  desc "Kotlin/JVM coding agent CLI"
  homepage "https://github.com/alpatrykos/kotlin-agent"
  url "https://github.com/alpatrykos/kotlin-agent/releases/download/v0.1.0/agent-0.1.0.tar"
  version "0.1.0"
  sha256 "b08151a1e9437f41179dbcb758317924cbe987aeb84c3ff86448a335e1c00b1d"
  license "MIT"

  depends_on "openjdk"

  def install
    libexec.install Dir["*"]
    bin.env_script_all_files libexec/"bin", Language::Java.overridable_java_home_env("17+")
  end

  test do
    assert_match "agent #{version}", shell_output("#{bin}/agent version")
    assert_match "apply_patch", shell_output("#{bin}/agent tools")
  end
end
