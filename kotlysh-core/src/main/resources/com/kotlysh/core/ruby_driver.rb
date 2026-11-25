# Trusted scriptlets share top-level Ruby state, separate from driver locals.
require 'base64'
require 'json'

module KotlyshRubyDriver
  # Keep transport operations independent of the user's top-level constants
  # and singleton-method customizations. Shared Ruby objects remain trusted.
  JSON_PARSE = JSON.method(:parse)
  JSON_GENERATE = JSON.method(:generate)
  BASE64_DECODE = Base64.method(:strict_decode64)
  BASE64_ENCODE = Base64.method(:strict_encode64)
  EVALUATE = Kernel.method(:eval)
  UTF8 = Encoding::UTF_8
  ERROR_CLASS = Exception
  ARGUMENT_ERROR_CLASS = ArgumentError
  private_constant :JSON_PARSE, :JSON_GENERATE, :BASE64_DECODE, :BASE64_ENCODE,
                   :EVALUATE, :UTF8, :ERROR_CLASS, :ARGUMENT_ERROR_CLASS

  def self.utf8(text)
    text.encode(UTF8, invalid: :replace, undef: :replace)
  end

  def self.exception_type(error)
    name = error.class.name
    name && !name.empty? ? utf8(name) : 'Error'
  rescue ERROR_CLASS
    'Error'
  end

  def self.exception_message(error)
    utf8(error.message.to_s)
  rescue ERROR_CLASS
    '<unprintable error>'
  end

  def self.run(input, output, diagnostic, namespace)
    input.set_encoding(UTF8)
    output.set_encoding(UTF8)
    diagnostic.set_encoding(UTF8)
    output.sync = diagnostic.sync = true

    input.each_line do |line|
      message = JSON_PARSE.call(line)
      marker = message.fetch('marker')
      status = 0
      begin
        raise ARGUMENT_ERROR_CLASS, 'Unsupported Kotlysh protocol version' unless message['version'] == 1
        raise ARGUMENT_ERROR_CLASS, 'Unsupported Ruby operation' unless message['op'] == 'exec'
        source = BASE64_DECODE.call(message.fetch('code')).force_encoding(UTF8)
        raise ARGUMENT_ERROR_CLASS, 'Ruby source must be valid UTF-8' unless source.valid_encoding?
        EVALUATE.call(source, namespace, '<kotlysh>', 1)
        response = { 'version' => 1, 'kind' => 'unit' }
      rescue ERROR_CLASS => error
        # SyntaxError, SystemExit and Interrupt are failed fragments too.
        status = 1
        type, detail = exception_type(error), exception_message(error)
        begin
          diagnostic.write(utf8(error.full_message(highlight: false)))
        rescue ERROR_CLASS
          diagnostic.write("#{type}: #{detail}\n")
        end
        response = { 'version' => 1, 'kind' => 'error', 'type' => type, 'message' => detail }
      end
      payload = BASE64_ENCODE.call(JSON_GENERATE.call(response))
      output.write("#{marker}:#{status}:#{payload}\n")
      diagnostic.write("#{marker}:#{status}\n")
    end
  end
end

KotlyshRubyDriver.run(STDIN, STDOUT, STDERR, TOPLEVEL_BINDING)
