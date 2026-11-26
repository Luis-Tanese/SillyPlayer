#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <string>
#include <vector>
#include <memory>
#include <algorithm>
#include <cmath>
#include <json.hpp>
#include <taglib/fileref.h>
#include <taglib/tiostream.h>
#include <taglib/tpropertymap.h>
#include <taglib/mpegfile.h>
#include <taglib/id3v2tag.h>
#include <taglib/attachedpictureframe.h>
#include <taglib/flacfile.h>
#include <taglib/flacpicture.h>
#include <taglib/mp4file.h>
#include <taglib/mp4tag.h>
#include <taglib/mp4coverart.h>
#include <taglib/opusfile.h>
#include <taglib/vorbisfile.h>
#include <taglib/xiphcomment.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/audio_fifo.h>
}
using json=nlohmann::json;
static std::string str(JNIEnv*e,jstring s){if(!s)return {};auto c=e->FindClass("java/lang/String");auto m=e->GetMethodID(c,"getBytes","(Ljava/lang/String;)[B");auto encoding=e->NewStringUTF("UTF-8");auto a=(jbyteArray)e->CallObjectMethod(s,m,encoding);std::string out(e->GetArrayLength(a),'\0');e->GetByteArrayRegion(a,0,out.size(),reinterpret_cast<jbyte*>(out.data()));e->DeleteLocalRef(a);e->DeleteLocalRef(encoding);e->DeleteLocalRef(c);return out;}
static jstring jstr(JNIEnv*e,const std::string&s){auto a=e->NewByteArray(s.size());e->SetByteArrayRegion(a,0,s.size(),reinterpret_cast<const jbyte*>(s.data()));auto c=e->FindClass("java/lang/String");auto m=e->GetMethodID(c,"<init>","([BLjava/lang/String;)V");auto charset=e->NewStringUTF("UTF-8");auto result=(jstring)e->NewObject(c,m,a,charset);e->DeleteLocalRef(a);e->DeleteLocalRef(c);e->DeleteLocalRef(charset);return result;}
static void fail(JNIEnv*e,const std::string&message){e->ThrowNew(e->FindClass("java/io/IOException"),message.c_str());}
class Descriptor:public TagLib::IOStream {
 int fd;std::string label;
public:
 Descriptor(int source,std::string name):fd(dup(source)),label(std::move(name)){if(fd>=0)lseek(fd,0,SEEK_SET);}
 ~Descriptor()override{if(fd>=0)close(fd);}
 TagLib::FileName name()const override{return label.c_str();}
 TagLib::ByteVector readBlock(unsigned long n)override{if(n>16*1024*1024)return {};TagLib::ByteVector data(static_cast<unsigned int>(n));auto got=read(fd,data.data(),n);if(got<0)return {};data.resize(got);return data;}
 void writeBlock(const TagLib::ByteVector&)override{}
 void insert(const TagLib::ByteVector&,unsigned long,unsigned long)override{}
 void removeBlock(unsigned long,unsigned long)override{}
 bool readOnly()const override{return true;}
 bool isOpen()const override{return fd>=0;}
 void seek(long offset,Position origin=Beginning)override{lseek(fd,offset,origin==Beginning?SEEK_SET:origin==Current?SEEK_CUR:SEEK_END);}
 void clear()override{}
 long tell()const override{return lseek(fd,0,SEEK_CUR);}
 long length()override{struct stat s{};return fstat(fd,&s)==0?s.st_size:0;}
 void truncate(long)override{}
};
static std::string field(const TagLib::PropertyMap&p,const char*name){auto values=p[name];return values.isEmpty()?std::string():values.front().to8Bit(true);}
static TagLib::Ogg::XiphComment *xiphTag(TagLib::File *file) {
 if(auto opus=dynamic_cast<TagLib::Ogg::Opus::File*>(file))return opus->tag();
 if(auto vorbis=dynamic_cast<TagLib::Ogg::Vorbis::File*>(file))return vorbis->tag();
 return nullptr;
}
static TagLib::ByteVector cover(TagLib::File*f){
 if(auto tag=xiphTag(f)){auto pictures=tag->pictureList();if(!pictures.isEmpty())return pictures.front()->data();}
 if(auto m=dynamic_cast<TagLib::MPEG::File*>(f)){auto tag=m->ID3v2Tag();if(tag){auto frames=tag->frameList("APIC");if(!frames.isEmpty())if(auto picture=dynamic_cast<TagLib::ID3v2::AttachedPictureFrame*>(frames.front()))return picture->picture();}}
 if(auto flac=dynamic_cast<TagLib::FLAC::File*>(f)){auto list=flac->pictureList();if(!list.isEmpty())return list.front()->data();}
 if(auto mp4=dynamic_cast<TagLib::MP4::File*>(f)){auto items=mp4->tag()->itemMap();if(items.contains("covr")){auto list=items["covr"].toCoverArtList();if(!list.isEmpty())return list.front().data();}}
 return {};
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_sillyplayer_media_NativeMedia_tags(JNIEnv*e,jobject,jint fd,jstring name){
 try{Descriptor stream(fd,str(e,name));TagLib::FileRef ref(&stream,true,TagLib::AudioProperties::Fast);if(ref.isNull())return jstr(e,"{}");auto p=ref.file()->properties();auto tag=ref.tag();auto comment=tag?tag->comment().to8Bit(true):std::string();if(comment.empty())comment=field(p,"COMMENT");auto purl=field(p,"PURL");if(purl.empty())purl=field(p,"URL");if(purl.empty())purl=field(p,"WOAS");json out={{"title",tag?tag->title().to8Bit(true):""},{"artist",tag?tag->artist().to8Bit(true):""},{"album",tag?tag->album().to8Bit(true):""},{"albumArtist",field(p,"ALBUMARTIST")},{"genre",tag?tag->genre().to8Bit(true):""},{"comment",comment},{"purl",purl},{"year",tag?tag->year():0},{"number",tag?tag->track():0},{"duration",ref.audioProperties()?ref.audioProperties()->lengthInMilliseconds()/1000.0:0.0}};return jstr(e,out.dump());}catch(const std::exception&x){fail(e,x.what());return nullptr;}}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_sillyplayer_media_NativeMedia_artwork(JNIEnv*e,jobject,jint fd,jstring name){try{Descriptor stream(fd,str(e,name));TagLib::FileRef ref(&stream,false);if(ref.isNull())return nullptr;auto data=cover(ref.file());if(data.isEmpty()||data.size()>8*1024*1024)return nullptr;auto result=e->NewByteArray(data.size());e->SetByteArrayRegion(result,0,data.size(),reinterpret_cast<const jbyte*>(data.data()));return result;}catch(...){return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_sillyplayer_media_NativeMedia_edit(JNIEnv*e,jobject,jstring path,jstring tags,jstring artwork){try{auto p=str(e,path);TagLib::FileRef ref(p.c_str());if(ref.isNull()||ref.file()->readOnly())return false;auto values=json::parse(str(e,tags));auto properties=ref.file()->properties();for(auto name:{"title","artist","album","albumArtist","genre","comment"})if(values.contains(name)){auto key=std::string(name);if(key=="albumArtist")key="ALBUMARTIST";else std::transform(key.begin(),key.end(),key.begin(),::toupper);auto value=values[name].get<std::string>();if(value.empty())properties.erase(key.c_str());else properties.replace(key.c_str(),TagLib::StringList(TagLib::String(value,TagLib::String::UTF8)));}ref.file()->setProperties(properties);auto imagePath=str(e,artwork);if(!imagePath.empty()){FILE*f=fopen(imagePath.c_str(),"rb");if(!f)return false;std::vector<char> bytes;char block[65536];size_t n;while((n=fread(block,1,sizeof block,f))>0 && bytes.size()<=8*1024*1024)bytes.insert(bytes.end(),block,block+n);fclose(f);if(bytes.empty()||bytes.size()>8*1024*1024)return false;TagLib::ByteVector data(bytes.data(),bytes.size());bool png=bytes.size()>4&&static_cast<unsigned char>(bytes[0])==137;
 if(auto m=dynamic_cast<TagLib::MPEG::File*>(ref.file())){auto tag=m->ID3v2Tag(true);tag->removeFrames("APIC");auto picture=new TagLib::ID3v2::AttachedPictureFrame;picture->setType(TagLib::ID3v2::AttachedPictureFrame::FrontCover);picture->setMimeType(png?"image/png":"image/jpeg");picture->setPicture(data);tag->addFrame(picture);}
 else if(auto flac=dynamic_cast<TagLib::FLAC::File*>(ref.file())){flac->removePictures();auto picture=new TagLib::FLAC::Picture;picture->setType(TagLib::FLAC::Picture::FrontCover);picture->setMimeType(png?"image/png":"image/jpeg");picture->setData(data);flac->addPicture(picture);}
 else if(auto mp4=dynamic_cast<TagLib::MP4::File*>(ref.file())){TagLib::MP4::CoverArtList covers;covers.append(TagLib::MP4::CoverArt(png?TagLib::MP4::CoverArt::PNG:TagLib::MP4::CoverArt::JPEG,data));mp4->tag()->setItem("covr",TagLib::MP4::Item(covers));}else if(auto tag=xiphTag(ref.file())){tag->removeAllPictures();auto picture=new TagLib::FLAC::Picture;picture->setType(TagLib::FLAC::Picture::FrontCover);picture->setMimeType(png?"image/png":"image/jpeg");picture->setData(data);tag->addPicture(picture);}else return false;
 }return ref.file()->save();}catch(const std::exception&x){fail(e,x.what());return false;}}
struct Samples {uint8_t**data=nullptr;~Samples(){if(data){av_freep(&data[0]);av_freep(&data);}}};
struct PacketOwner {AVPacket*packet=av_packet_alloc();~PacketOwner(){av_packet_free(&packet);}};
struct Progress {JNIEnv*e;jobject callback;jmethodID method;bool cancelled=false;bool update(int64_t n,int64_t total){if(e->ExceptionCheck())return false;cancelled=!e->CallBooleanMethod(callback,method,jlong(n),jlong(total));return !cancelled;} };
static int interrupt(void*opaque){auto p=static_cast<Progress*>(opaque);return !p->update(0,-1);}
static std::string avError(int value){char buffer[AV_ERROR_MAX_STRING_SIZE];av_strerror(value,buffer,sizeof buffer);return buffer;}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_sillyplayer_media_NativeMedia_convert(JNIEnv*e,jobject,jstring input,jstring output,jboolean mp3,jstring certificates,jstring headers,jobject callback){
 auto in=str(e,input),out=str(e,output),ca=str(e,certificates),h=str(e,headers);Progress progress{e,callback,e->GetMethodID(e->GetObjectClass(callback),"update","(JJ)Z")};
 AVFormatContext*src=avformat_alloc_context(),*dst=nullptr;AVCodecContext*decoder=nullptr,*encoder=nullptr;SwrContext*swr=nullptr;AVAudioFifo*fifo=nullptr;AVFrame*decoded=av_frame_alloc(),*encoded=av_frame_alloc();AVPacket*packet=av_packet_alloc();AVDictionary*options=nullptr;bool success=false;int64_t pts=0;
 auto require=[&](int rc){if(rc<0)throw std::runtime_error(avError(rc));};
 auto release=[&]{av_dict_free(&options);if(fifo)av_audio_fifo_free(fifo);swr_free(&swr);avcodec_free_context(&decoder);avcodec_free_context(&encoder);av_frame_free(&decoded);av_frame_free(&encoded);av_packet_free(&packet);avformat_close_input(&src);if(dst){if(dst->pb)avio_closep(&dst->pb);avformat_free_context(dst);}};
 try{
  if(!src||!decoded||!encoded||!packet)throw std::bad_alloc();
  src->interrupt_callback={interrupt,&progress};av_dict_set(&options,"tls_verify","1",0);if(!ca.empty())av_dict_set(&options,"ca_file",ca.c_str(),0);if(!h.empty())av_dict_set(&options,"headers",h.c_str(),0);av_dict_set(&options,"rw_timeout","20000000",0);
  require(avformat_open_input(&src,in.c_str(),nullptr,&options));require(avformat_find_stream_info(src,nullptr));int index=av_find_best_stream(src,AVMEDIA_TYPE_AUDIO,-1,-1,nullptr,0);require(index);auto stream=src->streams[index];
  require(avformat_alloc_output_context2(&dst,nullptr,mp3?"mp3":nullptr,out.c_str()));auto target=avformat_new_stream(dst,nullptr);if(!target)throw std::bad_alloc();av_dict_copy(&dst->metadata,src->metadata,0);
  if(mp3){auto dec=avcodec_find_decoder(stream->codecpar->codec_id);if(!dec)throw std::runtime_error("Unsupported audio decoder");decoder=avcodec_alloc_context3(dec);if(!decoder)throw std::bad_alloc();require(avcodec_parameters_to_context(decoder,stream->codecpar));require(avcodec_open2(decoder,dec,nullptr));auto enc=avcodec_find_encoder_by_name("libmp3lame");if(!enc)throw std::runtime_error("MP3 encoder is unavailable");encoder=avcodec_alloc_context3(enc);if(!encoder)throw std::bad_alloc();encoder->sample_rate=decoder->sample_rate>48000?48000:decoder->sample_rate;encoder->sample_fmt=enc->sample_fmts[0];encoder->time_base={1,encoder->sample_rate};av_channel_layout_default(&encoder->ch_layout,std::min(2,decoder->ch_layout.nb_channels));encoder->flags|=AV_CODEC_FLAG_QSCALE;encoder->global_quality=2*FF_QP2LAMBDA;if(dst->oformat->flags&AVFMT_GLOBALHEADER)encoder->flags|=AV_CODEC_FLAG_GLOBAL_HEADER;require(avcodec_open2(encoder,enc,nullptr));require(avcodec_parameters_from_context(target->codecpar,encoder));target->time_base=encoder->time_base;require(swr_alloc_set_opts2(&swr,&encoder->ch_layout,encoder->sample_fmt,encoder->sample_rate,&decoder->ch_layout,decoder->sample_fmt,decoder->sample_rate,0,nullptr));require(swr_init(swr));fifo=av_audio_fifo_alloc(encoder->sample_fmt,encoder->ch_layout.nb_channels,encoder->frame_size*2);if(!fifo)throw std::bad_alloc();
  }else {require(avcodec_parameters_copy(target->codecpar,stream->codecpar));target->codecpar->codec_tag=0;target->time_base=stream->time_base;}
  require(avio_open(&dst->pb,out.c_str(),AVIO_FLAG_WRITE));require(avformat_write_header(dst,nullptr));
  auto drain=[&]{PacketOwner owner;AVPacket*result=owner.packet;if(!result)throw std::bad_alloc();while(true){int rc=avcodec_receive_packet(encoder,result);if(rc==AVERROR(EAGAIN)||rc==AVERROR_EOF)break;require(rc);av_packet_rescale_ts(result,encoder->time_base,target->time_base);result->stream_index=target->index;require(av_interleaved_write_frame(dst,result));av_packet_unref(result);}};
  auto consume=[&](bool final){while(av_audio_fifo_size(fifo)>=encoder->frame_size || (final&&av_audio_fifo_size(fifo)>0)){av_frame_unref(encoded);encoded->format=encoder->sample_fmt;encoded->sample_rate=encoder->sample_rate;require(av_channel_layout_copy(&encoded->ch_layout,&encoder->ch_layout));encoded->nb_samples=std::min(av_audio_fifo_size(fifo),encoder->frame_size);require(av_frame_get_buffer(encoded,0));av_audio_fifo_read(fifo,reinterpret_cast<void**>(encoded->data),encoded->nb_samples);encoded->pts=pts;pts+=encoded->nb_samples;require(avcodec_send_frame(encoder,encoded));drain();}};
  auto receive=[&]{while(true){int rc=avcodec_receive_frame(decoder,decoded);if(rc==AVERROR(EAGAIN)||rc==AVERROR_EOF)break;require(rc);int n=av_rescale_rnd(swr_get_delay(swr,decoder->sample_rate)+decoded->nb_samples,encoder->sample_rate,decoder->sample_rate,AV_ROUND_UP);Samples samples;auto &data=samples.data;require(av_samples_alloc_array_and_samples(&data,nullptr,encoder->ch_layout.nb_channels,n,encoder->sample_fmt,0));int got=swr_convert(swr,data,n,const_cast<const uint8_t**>(decoded->extended_data),decoded->nb_samples);require(got);require(av_audio_fifo_realloc(fifo,av_audio_fifo_size(fifo)+got));av_audio_fifo_write(fifo,reinterpret_cast<void**>(data),got);consume(false);av_frame_unref(decoded);}};
  int rc;while((rc=av_read_frame(src,packet))>=0){if(!progress.update(src->pb?src->pb->bytes_read:0,src->pb?avio_size(src->pb):-1))throw std::runtime_error("Cancelled");if(packet->stream_index==index){if(mp3){require(avcodec_send_packet(decoder,packet));receive();}else{av_packet_rescale_ts(packet,stream->time_base,target->time_base);packet->stream_index=target->index;packet->pos=-1;require(av_interleaved_write_frame(dst,packet));}}av_packet_unref(packet);}if(rc!=AVERROR_EOF)require(rc);
  if(mp3){require(avcodec_send_packet(decoder,nullptr));receive();while(swr_get_delay(swr,decoder->sample_rate)>0){int n=av_rescale_rnd(swr_get_delay(swr,decoder->sample_rate),encoder->sample_rate,decoder->sample_rate,AV_ROUND_UP);Samples samples;auto &data=samples.data;require(av_samples_alloc_array_and_samples(&data,nullptr,encoder->ch_layout.nb_channels,n,encoder->sample_fmt,0));int got=swr_convert(swr,data,n,nullptr,0);if(got>0){require(av_audio_fifo_realloc(fifo,av_audio_fifo_size(fifo)+got));av_audio_fifo_write(fifo,reinterpret_cast<void**>(data),got);}require(got);if(got==0)break;consume(false);}consume(true);require(avcodec_send_frame(encoder,nullptr));drain();}require(av_write_trailer(dst));success=true;
 }catch(const std::exception&x){if(!e->ExceptionCheck())fail(e,x.what());}release();if(!success)unlink(out.c_str());return success;
}

