class Ccode < Formula
  desc "Kotlin/JVM coding agent CLI"
  homepage "https://github.com/alpatrykos/crackedcode"
  url "https://github.com/alpatrykos/crackedcode/releases/download/v0.1.0/ccode-0.1.0.tar"
  version "0.1.0"
  sha256 "d27267a6ff01272609748d6d3a04ff0c4f4ee515cd0e0a7910913e13ae167a85"
  license "MIT"

  depends_on "openjdk"

  def install
    libexec.install Dir["*"]
    bin.env_script_all_files libexec/"bin", Language::Java.overridable_java_home_env("17+")
  end

  test do
    assert_match "ccode #{version}", shell_output("#{bin}/ccode version")
    assert_match "apply_patch", shell_output("#{bin}/ccode tools")
  end
end
